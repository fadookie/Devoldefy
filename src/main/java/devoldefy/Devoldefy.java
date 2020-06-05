package devoldefy;

import com.google.gson.Gson;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.impl.MappingSetImpl;
import org.cadixdev.lorenz.impl.MappingSetModelFactoryImpl;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.mixin.MixinRemapper;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Devoldefy {
    public static boolean test = false;
    
    private static final String CSV = "http://export.mcpbot.bspk.rs/mcp_{csv_type}_nodoc/{csv_build}-{mc_version}/mcp_{csv_type}_nodoc-{csv_build}-{mc_version}.zip";
    private static final String SRG = "https://raw.githubusercontent.com/MinecraftForge/MCPConfig/master/versions/{mc_version}/joined.tsrg";
    private static final String SRG_NEW = "https://raw.githubusercontent.com/MinecraftForge/MCPConfig/master/versions/release/{mc_version}/joined.tsrg";
    private static final String YARN = "http://maven.modmuss50.me/net/fabricmc/yarn/{target_minecraft_version}+build.{yarn_build}/yarn-{target_minecraft_version}+build.{yarn_build}.jar";
    private static final String YARN_V2 = "http://maven.modmuss50.me/net/fabricmc/yarn/{target_minecraft_version}+build.{yarn_build}/yarn-{target_minecraft_version}+build.{yarn_build}-v2.jar";
    
    public static final boolean needsConfirmation = false;
    
    public static void main(String[] args) throws Exception {
        
        
        String configFileName;
        
        if (args.length == 0) {
            System.out.println("Input Config File Name:");
            configFileName = new Scanner(System.in).nextLine().trim();
        }
        else {
            configFileName = args[0].trim();
        }
        
        File configFile = new File(configFileName + ".json");
        
        if (!configFile.exists()) {
            throw new RuntimeException("Cannot find config file");
        }
        
        String data = Files.lines(configFile.toPath()).collect(Collectors.joining());
        Config config = (new Gson()).fromJson(data, Config.class);
        
        String mcpVersion = config.mcpGameVersion;
        String mcpChannel = config.mcpChannel;
        String mcpBuild = config.mcpBuild;
        
        String yarnVersion = config.yarnGameVersion;
        String yarnBuild = config.yarnBuild;
        
        boolean mcpToYarn = !config.additionalArguments.contains("invert");
        
        String sourceRoot = mcpToYarn ? config.mcpSourceCode : config.yarnSourceCode;
        String targetRoot = mcpToYarn ? config.yarnSourceCode : config.mcpSourceCode;
        
        String[] classPath = config.classpath;
        
        perform(
            mcpVersion,
            mcpChannel,
            mcpBuild,
            yarnVersion,
            yarnBuild,
            sourceRoot,
            targetRoot,
            Arrays.stream(classPath),
            mcpToYarn,
            true,
            new File(configFileName + "_cache")
        );
        
        if (config.copiedSubPackage != null && !config.copiedSubPackage.isEmpty()) {
            Path copyFrom = new File(targetRoot).toPath().resolve(config.copiedSubPackage);
            Path copyTo = new File(config.copyTargetDir).toPath();
            System.out.println("Copy from " + copyFrom);
            System.out.println("Copy to " + copyTo);
            
            if (needsConfirmation) {
                System.out.println("Input \"confirm\" to copy");
                
                String confirmMessage = new Scanner(System.in).nextLine().trim();
                
                if (!confirmMessage.equals("confirm")) {
                    System.out.println("didn't copy");
                    return;
                }
            }
            
            deleteDirectory(copyTo);
            copyDirectory(copyFrom, copyTo);
        }
    }
    
    private static void copyDirectory(Path sourcePath, Path targetPath) throws IOException {
        Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(
                final Path dir,
                final BasicFileAttributes attrs
            ) throws IOException {
                Files.createDirectories(targetPath.resolve(sourcePath
                    .relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(
                final Path file,
                final BasicFileAttributes attrs
            ) throws IOException {
                Files.copy(
                    file,
                    targetPath.resolve(sourcePath.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    private static void perform(
        String mcpVersion,
        String mcpChannel,
        String mcpBuild,
        String yarnVersion,
        String yarnBuild,
        String sourceRoot,
        String targetRoot,
        Stream<String> classPathLines,
        boolean mcpToYarn,
        boolean remapClientServerMarker,
        File cacheFileDir
    ) throws Exception {
        
        System.out.println("Begin Downloading");
        
        String csvUrl = CSV.replace("{mc_version}", mcpVersion).replace(
            "{csv_type}",
            mcpChannel
        ).replace("{csv_build}", mcpBuild);
        String srgUrl = SRG_NEW.replace("{mc_version}", yarnVersion);
        String yarnUrl = YARN.replace("{target_minecraft_version}", yarnVersion).replace(
            "{yarn_build}",
            yarnBuild
        );
        
        Mappings srg = readTsrg(
            new Scanner(download(srgUrl, cacheFileDir)),
            readCsv(new Scanner(extract(
                download(csvUrl, cacheFileDir),
                "fields.csv",
                cacheFileDir
            ))),
            readCsv(new Scanner(extract(
                download(csvUrl, cacheFileDir),
                "methods.csv",
                cacheFileDir
            )))
        );
        
        Mappings yarn;
        yarn = readYarnV1(
            new Scanner(extract(download(yarnUrl, cacheFileDir), "mappings/mappings.tiny",
                cacheFileDir
            )),
            "official", "named"
        );
        
        if (remapClientServerMarker) {
            srg.classes.put(
                "O_O",
                "net/minecraftforge/api/distmarker/OnlyIn"
            );
            srg.classes.put(
                "o_o",
                "net/minecraftforge/api/distmarker/Dist"
            );
            yarn.classes.put(
                "O_O",
                "net/fabricmc/api/Environment"
            );
            yarn.classes.put(
                "o_o",
                "net/fabricmc/api/EnvType"
            );
        }
        
        System.out.println("Downloaded");
        
        Mappings mappings = srg.invert().chain(yarn, false);
        
        if (!mcpToYarn) {
            mappings = mappings.invert();
        }
        
        if (mappings.classes.size() < 2000) {
            System.err.println(
                "Mapping number too few. Maybe the mapping is downloaded incompletely." +
                    " Try to delete cache."
            );
        }
        
        mappings.writeDebugMapping(new File(cacheFileDir, "chained_mapping"));
        srg.writeDebugMapping(new File(cacheFileDir, "mcp"));
        yarn.writeDebugMapping(new File(cacheFileDir, "yarn"));
        
        System.out.println("Start remapping");
        
        File sourceDir = new File(sourceRoot);
        File targetDir = new File(targetRoot);
        targetDir.mkdirs();
        
        deleteDirectory(targetDir.toPath());
        
        List<Path> classpath = classPathLines.map(
            line -> {
                File jarFile = new File(line);
                if (!jarFile.exists()) {
                    throw new IllegalStateException(line);
                }
                return jarFile.toPath();
            }
        ).collect(Collectors.toList());
        
        remap(sourceDir.toPath(), targetDir.toPath(), classpath, mappings);
        
        System.out.println("Finished");
    }
    
    private static void deleteDirectory(Path targetPath) throws IOException {
        Files.walk(targetPath).sorted(Comparator.reverseOrder()).map(Path::toFile)
            .forEach(File::delete);
    }
    
    private static File download(String url, File directory) throws IOException {
        System.out.println("downloading " + url);
        
        directory.mkdirs();
        File file = new File(directory, url.substring(url.lastIndexOf('/') + 1));
        
        if (!file.exists()) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        
        return file;
    }
    
    private static File extract(File zip, String path, File directory) throws IOException {
        directory.mkdirs();
        File file = new File(directory, path);
        file.mkdirs();
    
        try (ZipFile zipFile = new ZipFile(zip)) {
            InputStream is = null;
        
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                if (zipEntry.getName().equals(path)) {
                    is = zipFile.getInputStream(zipEntry);
                    break;
                }
            }
            
            if (is == null) {
                return null;
            }
            
            Files.copy(is, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        
        return file;
    }
    
    private static String hash(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
    
    private static Mappings readTsrg(
        Scanner s,
        Map<String, String> fieldNames,
        Map<String, String> methodNames
    ) {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        
        String currentClassA = null;
        String currentClassB = null;
        while (s.hasNextLine()) {
            String line = s.nextLine();
            
            if (!line.startsWith("\t")) {
                String[] parts = line.split(" ");
                classes.put(parts[0], parts[1]);
                currentClassA = parts[0];
                currentClassB = parts[1];
                continue;
            }
            
            line = line.substring(1);
            
            String[] parts = line.split(" ");
            
            if (parts.length == 2) {
                fields.put(
                    currentClassA + ":" + parts[0],
                    currentClassB + ":" + fieldNames.getOrDefault(parts[1], parts[1])
                );
            }
            else if (parts.length == 3) {
                methods.put(
                    currentClassA + ":" + parts[0] + parts[1],
                    currentClassB + ":" + methodNames.getOrDefault(parts[2], parts[2]) + parts[1]
                );
            }
        }
        
        Mappings mappings = new Mappings();
        mappings.classes.putAll(classes);
        mappings.fields.putAll(fields);
        methods.forEach((a, b) -> mappings.methods.put(a, remapMethodDescriptor(b, classes)));
        
        s.close();
        return mappings;
    }
    
    private static Map<String, String> readCsv(Scanner s) {
        Map<String, String> mappings = new LinkedHashMap<>();
        
        try (Scanner r = s) {
            r.nextLine();
            while (r.hasNextLine()) {
                String[] parts = r.nextLine().split(",");
                mappings.put(parts[0], parts[1]);
            }
        }
        
        s.close();
        return mappings;
    }
    
    private static Mappings readYarnV1(Scanner s, String from, String to) {
        String[] header = s.nextLine().split("\t");
        Map<String, Integer> columns = new HashMap<>();
        
        for (int i = 1; i < header.length; i++) {
            columns.put(header[i], i - 1);
        }
        
        int fromColumn = columns.get(from);
        int toColumn = columns.get(to);
        
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        
        while (s.hasNextLine()) {
            String[] line = s.nextLine().split("\t");
            switch (line[0]) {
                case "CLASS": {
                    classes.put(line[fromColumn + 1], line[toColumn + 1]);
                    break;
                }
                
                case "FIELD": {
                    fields.put(
                        line[1] + ":" + line[fromColumn + 3],
                        classes.get(line[1]) + ":" + line[toColumn + 3]
                    );
                    break;
                }
                
                case "METHOD": {
                    String m1 = line[1] + ":" + line[fromColumn + 3] + line[2];
                    String m2 = classes.get(line[1]) + ":" + line[toColumn + 3] + line[2];
                    methods.put(
                        m1,
                        m2
                    );
                    break;
                }
            }
        }
        
        Mappings mappings = new Mappings();
        mappings.classes.putAll(classes);
        mappings.fields.putAll(fields);
        methods.forEach((a, b) -> mappings.methods.put(a, remapMethodDescriptor(b, classes)));
        
        s.close();
        return mappings;
    }
    
    
    private static void remap(
        Path source,
        Path target,
        List<Path> classpath,
        Mappings mappings
    ) throws Exception {
        Mercury mercury = new Mercury();
        mercury.getClassPath().addAll(classpath);
        
        MappingSet mappingSet = new MappingSetImpl(new MappingSetModelFactoryImpl());
        mappings.classes.forEach((a, b) -> mappingSet
            .getOrCreateClassMapping(a)
            .setDeobfuscatedName(b)
        );
        
        mappings.fields.forEach((a, b) -> mappingSet
            .getOrCreateClassMapping(a.split(":")[0])
            .getOrCreateFieldMapping(a.split(":")[1])
            .setDeobfuscatedName(b.split(":")[1])
        );
        
        mappings.methods.forEach((a, b) -> {
            String a0 = a.split(":")[0];
            String a1 = a.split(":")[1];
            String b1 = b.split(":")[1];
            String binName = a1.substring(0, a1.indexOf('('));
            String descriptor = getDescriptor(a1);
            mappingSet
                .getOrCreateClassMapping(a0)
                .getOrCreateMethodMapping(binName, descriptor)
                .setDeobfuscatedName(b1.substring(0, b1.indexOf('(')));
        });
        
        mercury.getProcessors().add(MixinRemapper.create(mappingSet));
        mercury.getProcessors().add(new MyRemapper(mappingSet));
        
        mercury.rewrite(source, target);
    }
    
    private static String getDescriptor(String method) {
        try {
            StringBuilder result = new StringBuilder();
            
            Reader r = new StringReader(method);
            boolean started = false;
            while (true) {
                int c = r.read();
                
                if (c == -1) {
                    break;
                }
                
                if (c == '(') {
                    started = true;
                }
                
                if (started) {
                    result.append((char) c);
                }
            }
            
            return result.toString();
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }
    }
    
    private static String remapMethodDescriptor(String method, Map<String, String> classMappings) {
        try {
            Reader r = new StringReader(method);
            StringBuilder result = new StringBuilder();
            boolean started = false;
            boolean insideClassName = false;
            StringBuilder className = new StringBuilder();
            while (true) {
                int c = r.read();
                if (c == -1) {
                    break;
                }
                
                if (c == ';') {
                    insideClassName = false;
                    result.append(classMappings.getOrDefault(
                        className.toString(),
                        className.toString()
                    ));
                }
                
                if (insideClassName) {
                    className.append((char) c);
                }
                else {
                    result.append((char) c);
                }
                
                if (c == '(') {
                    started = true;
                }
                
                //qouteall changed
                if (started && c == 'L' && !insideClassName) {
                    insideClassName = true;
                    className.setLength(0);
                }
            }
            
            return result.toString();
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }
    }
    
    private static class Mappings {
        public final Map<String, String> classes = new LinkedHashMap<>();
        public final Map<String, String> fields = new LinkedHashMap<>();
        public final Map<String, String> methods = new LinkedHashMap<>();
    
        public Mappings chain(Mappings other, boolean defaultIfMissing) {
            Mappings result = new Mappings();
        
            if (defaultIfMissing) {
                classes.forEach((a, b) -> result.classes.put(
                    a, other.classes.getOrDefault(b, b)
                ));
                fields.forEach((a, b) -> result.fields.put(
                    a, other.fields.getOrDefault(b, b)
                ));
                methods.forEach((a, b) -> result.methods.put(
                    a, other.methods.getOrDefault(b, b)
                ));
            }
            else {
                classes.forEach((a, b) -> {
                    String s = other.classes.get(b);
                    if (s != null) {
                        result.classes.put(a, s);
                    }
                });
                fields.forEach((a, b) -> {
                    String s = other.fields.get(b);
                    if (s != null) {
                        result.fields.put(a, s);
                    }
                });
                methods.forEach((a, b) -> {
                    String s = other.methods.get(b);
                    if (s != null) {
                        result.methods.put(a, s);
                    }
                });
            }
        
        
            return result;
        }
    
        public Mappings invert() {
            Mappings result = new Mappings();
        
            classes.forEach((a, b) -> result.classes.put(b, a));
            fields.forEach((a, b) -> result.fields.put(b, a));
            methods.forEach((a, b) -> result.methods.put(b, a));
        
            return result;
        }
    
        public void writeDebugMapping(File dir) {
            dir.mkdirs();
            writeMappingData(this.classes, new File(dir, "classes.txt"));
            writeMappingData(this.fields, new File(dir, "fields.txt"));
            writeMappingData(this.methods, new File(dir, "methods.txt"));
        }
    
        private void writeMappingData(Map<String, String> data, File textFile) {
            try {
                textFile.createNewFile();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            try (FileWriter fileWriter = new FileWriter(textFile)) {
                data.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        try {
                            fileWriter.write(entry.getKey() + "->" + entry.getValue() + "\n");
                        }
                        catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                fileWriter.flush();
            }
            catch (IOException e) {
            }
        }
    }
    
}
