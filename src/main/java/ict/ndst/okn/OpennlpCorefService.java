package ict.ndst.okn;


import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import opennlp.tools.cmdline.parser.ParserTool;
import opennlp.tools.coref.DiscourseEntity;
import opennlp.tools.coref.Linker;
import opennlp.tools.coref.LinkerMode;
import opennlp.tools.coref.TreebankLinker;
import opennlp.tools.coref.mention.DefaultParse;
import opennlp.tools.coref.mention.Mention;
import opennlp.tools.coref.mention.MentionContext;
import opennlp.tools.parser.Parse;
import opennlp.tools.parser.Parser;
import opennlp.tools.parser.ParserFactory;
import opennlp.tools.parser.ParserModel;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;


class SimpleMention {
    public int entityId;
    public int sentenceNumber;
    public int spanStart;
    public int spanEnd;
    public int headSpanStart;
    public int headSpanEnd;

    public SimpleMention(int entityId, int sentenceNumber, int spanStart, int spanEnd, int headSpanStart, int headSpanEnd) {
        this.entityId = entityId;
        this.sentenceNumber = sentenceNumber;
        this.spanStart = spanStart;
        this.spanEnd = spanEnd;
        this.headSpanStart = headSpanStart;
        this.headSpanEnd = headSpanEnd;
    }
}


public class OpennlpCorefService {
    Parser chunkParser;
    Linker corefLinker;
    int minLength;

    public OpennlpCorefService(String chunkParseModelPath, String corefModelPath,
                               int minLength) throws IOException {
        /* Load chunk parse model */
        InputStream chunkParseModelFile = new FileInputStream(chunkParseModelPath);
        ParserModel parserModel = new ParserModel(chunkParseModelFile);
        chunkParser = ParserFactory.create(parserModel);
        /* Load coref link model */
        corefLinker = new TreebankLinker(corefModelPath, LinkerMode.TEST);
        this.minLength = minLength;
    }

    public DiscourseEntity[] corefenceResolutionIntolerant(String content) {
        try {
            /* Split sentences */
            String[] sentences = content.split("\n");
            int numSentences = sentences.length;
            /* Chunk parse each sentence */
            Parse[] parsedSentences = new Parse[numSentences];
            for (int i = 0; i < numSentences; i++) {
                Parse result = ParserTool.parseLine(sentences[i], chunkParser, 1)[0];
                parsedSentences[i] = result;
            }
            /* Get mentions */
            List<Mention> allMentions = new ArrayList<Mention>();
            for (int i = 0; i < numSentences; i++) {
                DefaultParse resultWrapper = new DefaultParse(parsedSentences[i], i);
                Mention[] mentions = corefLinker.getMentionFinder().getMentions(resultWrapper);
                for (Mention mention : mentions) {
                    if (mention.getParse() == null) {
                        Parse snp = new Parse(parsedSentences[i].getText(),
                                mention.getSpan(), "NML", 1.0, 0);
                        parsedSentences[i].insert(snp);
                        mention.setParse(new DefaultParse(snp, i));
                    }
                }
                allMentions.addAll(Arrays.asList(mentions));
            }
            Mention[] mentionArray = allMentions.toArray(new Mention[0]);
            return corefLinker.getEntities(mentionArray);
        } catch (Exception e) {
//            e.printStackTrace();
            return new DiscourseEntity[0];
        }
    }

    private int corefenceResolution(String inFilePath, String outFilePath) throws IOException {
        /* Read file */
        BufferedReader br = new BufferedReader(new FileReader(inFilePath));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
            sb.append("\n");
        }
        String content = sb.toString();
        /* Coreference Resolution */
        DiscourseEntity[] entities = corefenceResolutionIntolerant(content);
        /* Convert result */
        BufferedWriter outFile = new BufferedWriter(new FileWriter(outFilePath));
        for (DiscourseEntity entity: entities) {
            int entityId = entity.getId();
            int count = 0;
            for (Iterator<MentionContext> it = entity.getMentions(); it.hasNext(); ) {
                MentionContext mention = it.next();
                count++;
            }
            /* Filter short coref chains */
            if (count >= minLength) {
                for (Iterator<MentionContext> it = entity.getMentions(); it.hasNext(); ) {
                    MentionContext mention = it.next();
                    outFile.write(entityId + " ");
                    outFile.write(mention.getParse().getSentenceNumber() + " ");
                    outFile.write(mention.getSpan().getStart() + " ");
                    outFile.write(mention.getSpan().getEnd() + " ");
                    outFile.write(mention.getHeadSpan().getStart() + " ");
                    outFile.write(mention.getHeadSpan().getEnd() + " ");
                }
                outFile.write("\n");
            }
        }
        outFile.close();
        return 1;
    }

    /* Convert entities to response string */
    private String responseContent(DiscourseEntity[] entities) {
        StringBuilder sb = new StringBuilder();
        for (DiscourseEntity entity: entities) {
            int entityId = entity.getId();
            int count = 0;
            for (Iterator<MentionContext> it = entity.getMentions(); it.hasNext(); ) {
                MentionContext mention = it.next();
                count++;
            }
            /* Filter short coref chains */
            if (count >= minLength) {
                for (Iterator<MentionContext> it = entity.getMentions(); it.hasNext(); ) {
                    MentionContext mention = it.next();
                    sb.append(entityId).append(" ")
                            .append(mention.getParse().getSentenceNumber()).append(" ")
                            .append(mention.getSpan().getStart()).append(" ")
                            .append(mention.getSpan().getEnd()).append(" ")
                            .append(mention.getHeadSpan().getStart()).append(" ")
                            .append(mention.getHeadSpan().getEnd()).append(" ");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public void printResult(String filePath) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filePath));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
            sb.append("\n");
        }
        String content = sb.toString();
        DiscourseEntity[] entities = corefenceResolutionIntolerant(content);
        String result = responseContent(entities);
        System.out.println(result);
    }

    public void runService(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/coref", new HttpHandler() {
            @Override
            public void handle(HttpExchange httpExchange) throws IOException {
                if (httpExchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    Headers requestHeaders = httpExchange.getRequestHeaders();
                    int contentLength = Integer.parseInt(requestHeaders.getFirst("Content-length"));
                    InputStream is = httpExchange.getRequestBody();
                    byte[] data = new byte[contentLength];
                    int length = is.read(data, 0, contentLength);
                    String content = new String(data);
                    /* Coreference Resolution */
                    String[] contents = content.split("\n");
                    String inFilePath = contents[0];
                    String outFilePath = contents[1];
//                    DiscourseEntity[] entities = corefenceResolutionIntolerant(content);
                    int result = corefenceResolution(inFilePath, outFilePath);
//                    byte[] responseContent = responseContent(entities).getBytes();
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
        ArgumentParser parser = ArgumentParsers.newFor("opennlp-coref-service").build()
                .defaultHelp(true)
                .description("start up a local opennlp coref service.");
        parser.addArgument("--port").type(Integer.class).setDefault(9000)
                .help("port");
        parser.addArgument("--parse_model").setDefault("data/en-parser-chunking.bin")
                .help("chunk parsing model path");
        parser.addArgument("--coref_model").setDefault("model/")
                .help("coreference resolution model path");
        parser.addArgument("--min_length").type(Integer.class).setDefault(2)
                .help("Minimum length of the coreference chain.");
        Namespace opts = parser.parseArgs(args);
        int port = opts.getInt("port");
        String chunkParseModelPath = opts.getString("parse_model");
        String corefModelPath = opts.getString("coref_model");
        int minLength = opts.getInt("min_length");
        /* Initialize service object */
        OpennlpCorefService service = new OpennlpCorefService(chunkParseModelPath, corefModelPath, minLength);
        service.runService(port);
//        service.printResult("../sample/NYT_ENG_19940701.0052");
    }
}
