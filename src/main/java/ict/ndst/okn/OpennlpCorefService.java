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
import java.util.*;


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


/**
 * Opennlp Service
 */
public class OpennlpCorefService
{
    private static Parser chunkParser = null;
    private static Linker corefLinker = null;
    private static int minLength = 2;

    public static void main( String[] args ) throws ArgumentParserException, IOException {
        /* Parse arguments */
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
        Namespace opts = null;
        opts = parser.parseArgs(args);
        /* Set variables */
        int port = opts.getInt("port");
        String chunkParseModelPath = opts.getString("parse_model");
        String corefModelPath = opts.getString("coref_model");
        minLength = opts.getInt("min_length");
        /* Load coreference resolution model */
        chunkParser = loadParser(chunkParseModelPath);
        corefLinker = loadLinker(corefModelPath);
//        corefFile("../test.0003");
        /* Start http server */
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/coref", new corefHandler());
//        server.createContext("/parse", new chunkParseHandler());
        System.out.println("Server started.");
        server.start();
    }

    /* Coref Link a file, just for testing */
    static void corefFile(String fileName) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(fileName));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
            sb.append("\n");
        }
        String content = sb.toString();
        List<ArrayList<SimpleMention>> entities = coreferenceResolution(content);
        sb = new StringBuilder();
        for (ArrayList<SimpleMention> mentions : entities) {
            for (SimpleMention mention: mentions) {
                sb.append(mention.entityId).append(" ")
                        .append(mention.sentenceNumber).append(" ")
                        .append(mention.spanStart).append(" ")
                        .append(mention.spanEnd).append(" ")
                        .append(mention.headSpanStart).append(" ")
                        .append(mention.headSpanEnd).append(" ");
            }
            sb.append("\n");
        }
        System.out.println(sb.toString());
    }

    /* Handle coreference resolution POST data */
    static class corefHandler implements HttpHandler {
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
                /* Process request */
                List<ArrayList<SimpleMention>> entities = coreferenceResolution(content);
                StringBuilder sb = new StringBuilder();
                for (ArrayList<SimpleMention> mentions : entities) {
                    for (SimpleMention mention: mentions) {
                        sb.append(mention.entityId).append(" ")
                                .append(mention.sentenceNumber).append(" ")
                                .append(mention.spanStart).append(" ")
                                .append(mention.spanEnd).append(" ")
                                .append(mention.headSpanStart).append(" ")
                                .append(mention.headSpanEnd).append(" ");
                    }
                    sb.append("\n");
                }
                /* Send response */
                byte [] responseContent = sb.toString().getBytes();
                exchange.sendResponseHeaders(200, responseContent.length);
                OutputStream os = exchange.getResponseBody();
                os.write(responseContent);
                os.flush();
                os.close();
            }
        }
    }

    static class chunkParseHandler implements HttpHandler {
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
                /* Process request */
                String[] sentences = content.split("\n");
                int numSentences = sentences.length;
                StringBuffer resultBuffer = new StringBuffer();
                for (String sentence : sentences) {
                    Parse result = ParserTool.parseLine(sentence, chunkParser, 1)[0];
                    result.show(resultBuffer);
                    resultBuffer.append("\n");
                }
                /* Send response */
                byte [] responseContent = resultBuffer.toString().getBytes();
                exchange.sendResponseHeaders(200, responseContent.length);
                OutputStream os = exchange.getResponseBody();
                os.write(responseContent);
                os.flush();
                os.close();
            }
        }
    }

    /* Load parser */
    private static Parser loadParser(String modelPath) throws IOException {
//        System.out.println("Loading chunkparse model ...");
        InputStream is = new FileInputStream(modelPath);
        ParserModel model = new ParserModel(is);
//        System.out.println("Chunk parser loaded.");
        return ParserFactory.create(model);
    }

    /* Load linker */
    private static Linker loadLinker(String modelDir) throws IOException {
//        System.out.println("Loading coref model ...");
        return new TreebankLinker(modelDir, LinkerMode.TEST);
    }


    private static List<Mention> findMentions(Parse[] parsedSentences, boolean headedOnly) {
        List<Mention> allMentions = new ArrayList<Mention>();
        for (int i = 0; i < parsedSentences.length; i++) {
            DefaultParse resultWrapper = new DefaultParse(parsedSentences[i], i);
            try {
                Mention[] mentions = corefLinker.getMentionFinder().getMentions(resultWrapper);
                for (Mention mention : mentions) {
                    /* Created new mention is unable to get head,
                     *  which will cause problem in linker. */
                    if (mention.getParse() == null) {
                        Parse snp = new Parse(parsedSentences[i].getText(),
                                mention.getSpan(), "NML", 1.0, 0);
                        parsedSentences[i].insert(snp);
                        mention.setParse(new DefaultParse(snp, i));
                        if (!headedOnly) {
                            allMentions.add(mention);
                        }
                    } else {
                        allMentions.add(mention);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return allMentions;
    }

    /* Coreference resolution*/
    private static List<ArrayList<SimpleMention>> coreferenceResolution(String content) {
        String[] sentences = content.split("\n");
        int numSentences = sentences.length;
        /* Chunk parse each sentence */
        Parse[] parsedSentences = new Parse[numSentences];
        for (int i = 0; i < numSentences; i++){
            Parse result = ParserTool.parseLine(sentences[i], chunkParser, 1)[0];
            parsedSentences[i] = result;
        }
        Mention[] mentionList;
        DiscourseEntity[] entities;
        try {
            /* Find mentions */
            List<Mention> allMentions = findMentions(parsedSentences, false);
            /* Coreference resolution */
            mentionList = allMentions.toArray(new Mention[0]);
            entities = corefLinker.getEntities(mentionList);
        } catch (ArrayIndexOutOfBoundsException e) {
            List<Mention> allMentions = findMentions(parsedSentences, true);
            mentionList = allMentions.toArray(new Mention[0]);
            entities = corefLinker.getEntities(mentionList);
        }
        /* Get result clusters */
        List<ArrayList<SimpleMention>> results = new ArrayList<ArrayList<SimpleMention>>();
        for (DiscourseEntity entity: entities) {
            int entityId = entity.getId();
            List<MentionContext> temp = new ArrayList<MentionContext>();
            for (Iterator<MentionContext> it = entity.getMentions(); it.hasNext(); ) {
                MentionContext mentionContext = it.next();
                temp.add(mentionContext);
            }
            ArrayList<SimpleMention> tempMentions = new ArrayList<SimpleMention>();
            if (temp.size() >= minLength) {
                for (MentionContext mentionContext: temp) {
                    tempMentions.add(new SimpleMention(
                            entityId, mentionContext.getParse().getSentenceNumber(),
                            mentionContext.getSpan().getStart(), mentionContext.getSpan().getEnd(),
                            mentionContext.getHeadSpan().getStart(),
                            mentionContext.getHeadSpan().getEnd()));
                }
                results.add(tempMentions);
            }
        }
        return results;
    }
}
