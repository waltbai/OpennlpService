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
    SentenceDetectorME sentenceDetector;
    Tokenizer tokenizer;
    int port;
    String sentModelPath;
    String tokenModelPath;

    public OpennlpTokenizeService(String sentModelPath,
                                  String tokenModelPath) throws IOException {
        /* Load sent model */
        InputStream sentModelFile = new FileInputStream(sentModelPath);
        SentenceModel sentenceModel = new SentenceModel(sentModelFile);
        sentenceDetector = new SentenceDetectorME(sentenceModel);
        /* Load token model */
        InputStream tokenModelFile = new FileInputStream(tokenModelPath);
        TokenizerModel tokenizerModel = new TokenizerModel(tokenModelFile);
        tokenizer = new TokenizerME(tokenizerModel);
    }

    /* Sentence split and tokenize */
    public int tokenize(String inFilePath, String outFilePath) throws IOException {
        ObjectStream<String> paraStream =
                new TrimmedParagraphStream(new PlainTextByLineStream(new FileReader(inFilePath)));
        String par;
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

    public void runService(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/token", new HttpHandler() {
            @Override
            public void handle(HttpExchange httpExchange) throws IOException {
                /* Only receive POST data.*/
                if (httpExchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    /* Parse request */
                    Headers requestHeaders = httpExchange.getRequestHeaders();
                    int contentLength = Integer.parseInt(requestHeaders.getFirst("Content-length"));
                    InputStream is = httpExchange.getRequestBody();
                    byte[] data = new byte[contentLength];
                    int length = is.read(data, 0, contentLength);
                    String content = new String(data);
                    String[] contents = content.split("\n");
                    String inFilePath = contents[0];
                    String outFilePath = contents[1];
                    int result = tokenize(inFilePath, outFilePath);
                    byte[] responseContent = Integer.toString(result).getBytes();
                    httpExchange.sendResponseHeaders(200, responseContent.length);
                    OutputStream os = httpExchange.getResponseBody();
                    os.write(responseContent);
                    os.flush();
                    os.close();
                }
            }
        });
        System.out.println("Server started.");
        server.start();
    }

    public static void main( String[] args ) throws ArgumentParserException, IOException {
        /* Parse command line arguments */
        ArgumentParser parser = ArgumentParsers.newFor("opennlp-tokenize-service").build()
                .defaultHelp(true)
                .description("start up a local opennlp tokenize service.");
        parser.addArgument("--port").type(Integer.class).setDefault(9000)
                .help("port");
        parser.addArgument("--sent_model").setDefault("data/en-sent.bin")
                .help("Sentence model path");
        parser.addArgument("--token_model").setDefault("data/en-token.bin")
                .help("Tokenize model path");
        Namespace opts = parser.parseArgs(args);
        int port = opts.getInt("port");
        String sentModelPath = opts.getString("sent_model");
        String tokenModelPath = opts.getString("token_model");
        /* Initialize service object */
        OpennlpTokenizeService service = new OpennlpTokenizeService(sentModelPath, tokenModelPath);
        service.runService(port);
    }
}
