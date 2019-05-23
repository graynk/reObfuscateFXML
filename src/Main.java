import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {
    private static Map<String, String> mapping;

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.out.println("Enter the correct arguments: inJar outJar mapping unJarDir");
        }
        String inJar = args[0];
        String outJar = args[1];
        String mappingFile = args[2];
        String unJarDir = args[3];
        mapping = getMapping(new File(mappingFile));
        unzipJar(unJarDir, inJar);
        File root = new File(unJarDir);
        refxml(root);
        jar(root, new File(outJar));
    }

    private static void unzipJar(String destinationDir, String jarPath) throws IOException {
        File file = new File(jarPath);
        JarFile jar = new JarFile(file);
        // fist get all directories,
        // then make those directory on the destination Path
        Enumeration<JarEntry> enums = jar.entries();
        while (enums.hasMoreElements()) {
            JarEntry entry = enums.nextElement();

            String fileName = destinationDir + File.separator + entry.getName().replace("/", File.separator);
            File f = new File(fileName.substring(0, fileName.lastIndexOf(File.separator)));
            f.mkdirs();
        }

        //now create all files
        enums = jar.entries();
        while (enums.hasMoreElements()) {
            JarEntry entry = enums.nextElement();

            String fileName = destinationDir + File.separator + entry.getName();
            File f = new File(fileName);

            if (!f.isDirectory()) {
                InputStream is = jar.getInputStream(entry);
                FileOutputStream fos = new FileOutputStream(f);

                // write contents of 'is' to 'fos'
                while (is.available() > 0) {
                    fos.write(is.read());
                }

                fos.close();
                is.close();
            }
        }
    }

    private static Map<String, String> getMapping(File proguardMapping) throws IOException {
        Map<String, String> mapping = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(proguardMapping.toPath(), StandardCharsets.UTF_8)) {
            String lastClass = "";
            String line;
            while ((line = r.readLine()) != null) {
                int arrow = line.indexOf("->");
                int space = line.substring(0, arrow-1).lastIndexOf(' ');
                String original = line.substring(space+1, arrow-1); // if there's no space then index is -1 and it's just beginning of the string
                String obfuscated = line.substring(arrow+3);
                int weirdNumbers = original.lastIndexOf(':');
                if (weirdNumbers != -1) { // some methods get numbers after their name. I don't know what they mean
                    original = original.substring(0, weirdNumbers);
                }
                if (obfuscated.endsWith(":")) { // class declaration
                    obfuscated = obfuscated.substring(0, obfuscated.length()-1);
                    lastClass = obfuscated + ".";
                }
                int openingBracket = original.indexOf('(');
                if (openingBracket != -1) { // we need only names of methods, not their signature
                    original = original.substring(0, openingBracket);
                }
                if (lastClass.startsWith(original))
                    mapping.put(original, obfuscated);
                else
                    mapping.put(lastClass + original, obfuscated);
            }
        }
        return mapping;
    }

    private static void refxml(File source) throws IOException {
        if (source.exists() && source.isDirectory()) {
            for (File nestedFile: Objects.requireNonNull(source.listFiles()))
                refxml(nestedFile);
            return;
        }
        if (!source.getName().endsWith(".fxml")) return;
        File dest = new File(source.getPath() + "1");
        try (BufferedReader r = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(dest.toPath(), StandardCharsets.UTF_8)) {
            Pattern pattern = Pattern.compile("(fx:id|onAction|fx:controller|source)=\"([^\\s]+)\"");

            String controller = "";
            String line;
            List<String> currentKeys = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    String call = matcher.group(0);
                    String method = matcher.group(2).replace("#", "");
                    if (call.contains("controller")) {
                        controller = method + ".";
                        for (String key : currentKeys) {
                            String value = mapping.get(controller + key);
                            if (value == null) {
                                System.out.println(method + " is not found in mapping. File: " + source.getPath());
                            } else {
                                line = line.replace(key, value);
                            }
                        }
                        currentKeys.clear();
                        continue;
                    }
                    boolean isInclude = line.contains("include");
                    String qualifiedMethod = controller + method;
                    if (!isInclude && mapping.containsKey(qualifiedMethod)) {
                        String obfuscatedCall = call.replace(method, mapping.get(qualifiedMethod));
                        line = line.replace(call, obfuscatedCall);
//                    } else if (isInclude && mapping.containsKey(qualifiedMethod + "Controller")) { // injection mechanism for fx:include kills me
//                        String obfuscatedCall = call.replace(method, mapping.get(qualifiedMethod + "Controller"));
//                        line = line.replace(call, obfuscatedCall);
                    } else if (isInclude && call.contains("source")) {
                        // unobfuscated classname
                        method = method.replace(".fxml", "");
                        // obfuscated package path
                        String packagePath = source.getPath()
                                .replace(File.separator, ".")
                                .replace("dest.", "")
                                .replace(source.getName(), "");
                        // horribly inefficient, but I don't have time for much else and it doesn't matter anyway
                        // maybe I'll change it later, but I probably won't
                        for (String key : mapping.keySet()) {
                            String value = mapping.get(key);
                            if (value.startsWith(packagePath) && key.endsWith(method)) {
                                String obfuscatedName = value.replace(packagePath, "");
                                line = line.replace(method, obfuscatedName);
                            }
                        }
                    } else {
                        currentKeys.add(method);
                    }
                }
                try {
                    w.write(line);
                    w.newLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        source.delete();
        dest.renameTo(source);
    }

    private static void jar(File directory, File zipfile) throws IOException {
        URI base = directory.toURI();
        Deque<File> queue = new LinkedList<>();
        queue.push(directory);
        OutputStream out = new FileOutputStream(zipfile);
        Closeable res = out;
        try {
            ZipOutputStream zout = new ZipOutputStream(out);
            res = zout;
            while (!queue.isEmpty()) {
                directory = queue.pop();
                for (File kid : directory.listFiles()) {
                    String name = base.relativize(kid.toURI()).getPath();
                    if (kid.isDirectory()) {
                        queue.push(kid);
                        name = name.endsWith("/") ? name : name + "/";
                        zout.putNextEntry(new ZipEntry(name));
                    } else {
                        zout.putNextEntry(new ZipEntry(name));
                        copy(kid, zout);
                        zout.closeEntry();
                    }
                }
            }
        } finally {
            res.close();
        }
    }

    private static void copy(File file, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            while (true) {
                int readCount = in.read(buffer);
                if (readCount < 0) {
                    break;
                }
                out.write(buffer, 0, readCount);
            }
        }
    }
}
