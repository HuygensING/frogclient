package nl.knaw.huygens.pergamon;

import nu.xom.*;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;

import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;

/**
 * Wrapper for the Frog named-entity recognizer.
 * <p>
 * Instances of this class act as clients for a Frog running in TCP server mode
 * producing XML. To start such a server, issue
 * <pre>
 *     frog --skip=mptcla -S port -X
 * </pre>
 * where port is some number. The --skip is optional but prevents Frog from wasting
 * time to produce analyses that we don't use.
 */
public class FrogSocketClient {
  private final String host;
  private final int port;

  private static final Charset utf8 = Charset.forName("UTF-8");

  private static final String foliaNS = "http://ilk.uvt.nl/folia";

  private static final XPathContext foliaCtxt = new XPathContext("folia", foliaNS);

  public FrogSocketClient(String host, int port) throws IOException {
    this.host = host;
    this.port = port;
  }

  /**
   * Apply Frog's NER to the given sentence, after tokenizing it.
   * <p>
   * Tokenization is performed by OpenNLP, because letting Frog do it makes it
   * nearly impossible to construct the correct spans.
   */
  public List<Span> apply(String sentence) throws IOException, ParsingException {
    TokenizerModel tokModel = new TokenizerModel(this.getClass().getResourceAsStream("/nl-token.bin"));
    Tokenizer tok = new TokenizerME(tokModel);
    return apply(sentence, asList(tok.tokenizePos(sentence)));
  }

  /**
   * Apply Frog's NER to the given pre-tokenized text.
   *
   * @param text   Text to be fed to NER.
   * @param tokens Tokens spans within text.
   * @return A list of spans, the type (getType) of which is the entity class assigned to them by Frog.
   * @throws IOException
   * @throws ParsingException
   */
  public List<Span> apply(String text, List<Span> tokens) throws IOException, ParsingException {
    StringBuilder sb = new StringBuilder();

    try (Socket conn = new Socket(host, port)) {
      Writer w = new OutputStreamWriter(conn.getOutputStream(), utf8);
      writeTokens(text, tokens, w);
      w.flush();
      conn.shutdownOutput();

      BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), utf8));

      // Frog output is: an XML document, followed by "READY" on a line by itself, repeated
      // ad infinitum.
      for (String line; (line = r.readLine()) != null && !line.equals("READY"); ) {
        sb.append(line);
      }
      r.close();
    }

    Document doc = new nu.xom.Builder(false).build(new StringReader(sb.toString()));
    Nodes tokenNodes = doc.query("//folia:w", foliaCtxt);

    // Record an id->token index mapping to resolve entity spans, which Frog represents
    // by offset annotations.
    Map<String, Integer> idToIndex = IntStream.range(0, tokenNodes.size()).boxed()
      .collect(Collectors.toMap(i -> {
          Element elem = (Element) tokenNodes.get(i);
          return elem.getAttribute("id", "http://www.w3.org/XML/1998/namespace").getValue();
        },
        Function.identity()
      ));

    // A folia:entity contains a list of folia:wref elements pointing back to the tokens, e.g.,
    // <entity xml:id="untitled.p.1.s.1.entities.1.entity.1" class="per" confidence = "0" >
    //   <wref id="untitled.p.1.s.1.w.3" t="Ben"/>
    //   <wref id="untitled.p.1.s.1.w.4" t="Hur/>
    // </entity>
    Nodes entities = doc.query("//folia:entity", foliaCtxt);
    return IntStream.range(0, entities.size()).mapToObj(i -> {
      Element elem = (Element) entities.get(i);
      Nodes wrefs = elem.query(".//folia:wref", foliaCtxt);

      // Let's assume each entity mention is a contiguous span of tokens, served to us in
      // textual order, so we only need to look at the first and the last.
      String id = ((Element) wrefs.get(0)).getAttribute("id").getValue();
      int start = tokens.get(idToIndex.get(id)).getStart();
      id = ((Element) wrefs.get(wrefs.size() - 1)).getAttribute("id").getValue();
      int end = tokens.get(idToIndex.get(id)).getEnd();

      return new Span(start, end, elem.getAttribute("class").getValue());
    }).collect(Collectors.toList());
  }

  private void writeTokens(String text, List<Span> tokens, Writer w) throws IOException {
    Span prev = new Span(0, 0);
    for (Span span : tokens) {
      Span trimmed = span.trim(text);
      if (trimmed.length() == 0) {
        throw new IllegalArgumentException(
          String.format("Empty or all-whitespace token '%s' at %s",
            span.getCoveredText(text).toString(), span.toString()));
      } else if (span.crosses(prev)) {
        throw new IllegalArgumentException(
          String.format("crossing spans %s and %s", prev, span));
      } else if (span.getStart() < prev.getEnd()) {
        throw new IllegalArgumentException(
          String.format("unsorted spans: %s, then %s", prev, span));
      }
      w.write(trimmed.getCoveredText(text).toString());
      w.write('\n');
      prev = span;
    }
  }
}