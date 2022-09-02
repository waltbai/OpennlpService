package ict.ndst.okn;


import com.google.common.base.Joiner;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.ParagraphStream;
import opennlp.tools.util.PlainTextByLineStream;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;


class TrimmedParagraphStream extends ParagraphStream {
    public TrimmedParagraphStream(ObjectStream<String> lineStream) {
        super(lineStream);
    }

    public String read() throws IOException {
        StringBuilder paragraph = new StringBuilder();

        while (true) {
            String line = samples.read();

            // The last paragraph in the input might not
            // be terminated well with a new line at the end.
            if (line == null || line.trim().equals("")) {
                if (paragraph.length() > 0) {
                    return paragraph.toString();
                }
            } else {
                paragraph.append(line.trim()).append(" \n");
            }

            if (line == null)
                return null;
        }
    }
}

public class OpennlpTokenizeService {
    static SentenceDetectorME sentenceDetector;
    static Tokenizer tokenizer;

    public static void main( String[] args ) throws IOException, ArgumentParserException {
        /* Parse arguments */
        ArgumentParser parser = ArgumentParsers.newFor("opennlp-tokenize-service").build()
                .defaultHelp(true)
                .description("start up a local opennlp tokenize service.");
        parser.addArgument("--port").type(Integer.class).setDefault(9000)
                .help("port");
        parser.addArgument("--sent_model").setDefault("data/en-sent.bin")
                .help("Sentence model path");
        parser.addArgument("--token_model").setDefault("data/en-token.bin")
                .help("Tokenize model path");
        // parser.addArgument("--input").setDefault("")
        //         .help("Input file path");
        // parser.addArgument("--output").setDefault("")
        //         .help("Output file path");
        Namespace opts = null;
        opts = parser.parseArgs(args);
        /* Set variables */
        int port = opts.getInt("port");
        /* Load sent and token model */
        String sentModelPath = opts.getString("sent_model");
        String tokenModelPath = opts.getString("token_model");
        InputStream sentModelFile = new FileInputStream(sentModelPath);
        SentenceModel sentenceModel = new SentenceModel(sentModelFile);
        sentenceDetector = new SentenceDetectorME(sentenceModel);
        InputStream tokenModelFile = new FileInputStream(tokenModelPath);
        TokenizerModel tokenizerModel = new TokenizerModel(tokenModelFile);
        tokenizer = new TokenizerME(tokenizerModel);
        /* Start service */
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/token", new OpennlpTokenizeService.tokenizeHandler());
        System.out.println("Server started.");
        server.start();
        /* test */
//        String[] sentences = ssplit("../test.0002").toArray(new String[0]);
//        for (String sent : sentences) {
//            System.out.println("[SENT]: ");
//            System.out.println(sent);
//        }
    }

    static List<String> ssplit(String fileName) throws IOException {
//        BufferedReader br = new BufferedReader(new FileReader(fileName));
        ObjectStream<String> paraStream =
                new TrimmedParagraphStream(new PlainTextByLineStream(
                        new FileReader(fileName)));
        StringBuilder sb = new StringBuilder();
        String par;
        List<String> sentences = new ArrayList<String>();
        while ((par = paraStream.read()) != null) {
            sb.append(par);
            System.out.println("par:");
            System.out.println(par);
            sb.append("\n");
            String[] par_sentences = sentenceDetector.sentDetect(par);
            for (String sent : par_sentences) {
                sentences.add(sent.replace("\n", " "));
            }
        }
        String content = sb.toString();
        return sentences;
    }

    static int tokenizeFile(String inFilePath, String outFilePath) throws IOException {
        ObjectStream<String> paraStream =
                new TrimmedParagraphStream(new PlainTextByLineStream(new FileReader(inFilePath)));
        String par;
//        StringBuilder result = new StringBuilder();
        BufferedWriter outFile = new BufferedWriter(new FileWriter(outFilePath));
        Joiner whitespaceJoiner = Joiner.on(' ');
        while ((par = paraStream.read()) != null) {
            if (par.equals("<DOCEND>")) {
                /* Do not change the special separate token. */
                outFile.write(par);
            } else if (par.length() == 0) {
                outFile.write("\n");
            } else {
                String[] sentences = sentenceDetector.sentDetect(par);
                for (String sentence: sentences) {
                    String[] tokens = tokenizer.tokenize(sentence);
                    outFile.write(whitespaceJoiner.join(tokens) + "\n");
                }
            }
        }
        outFile.close();
        return 1;
    }

    static String tokenize(String content) throws IOException {
        ObjectStream<String> paraStream =
                new TrimmedParagraphStream(new PlainTextByLineStream(new StringReader(content)));
        String par;
        StringBuilder result = new StringBuilder();
        Joiner whitespaceJoiner = Joiner.on(' ');
        while ((par = paraStream.read()) != null) {
            if (par.equals("<DOCEND>")) {
                /* Do not change the special separate token. */
                result.append(par);
            } else if (par.length() == 0) {
                result.append("\n");
            } else {
                String[] sentences = sentenceDetector.sentDetect(par);
                for (String sentence: sentences) {
                    String[] tokens = tokenizer.tokenize(sentence);
                    result.append(whitespaceJoiner.join(tokens)).append("\n");
                }
            }
        }
        return result.toString();
    }

    private static class tokenizeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            /* Only receive POST data.*/
            if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                /* Parse request */
                Headers requestHeaders = exchange.getRequestHeaders();
                int contentLength = Integer.parseInt(requestHeaders.getFirst("Content-length"));
                InputStream is = exchange.getRequestBody();
                byte[] data = new byte[contentLength];
                int length = is.read(data, 0, contentLength);
                String content = new String(data);
                String[] contents = content.split("\n");
                String inFilePath = contents[0];
                String outFilePath = contents[1];
                /* Process request */
//                String result = tokenize(content);
                int result = tokenizeFile(inFilePath, outFilePath);
                /* Send response */
                byte [] responseContent = Integer.toString(result).getBytes();
                exchange.sendResponseHeaders(200, responseContent.length);
                OutputStream os = exchange.getResponseBody();
                os.write(responseContent);
                os.flush();
                os.close();
            }
        }
    }
}
