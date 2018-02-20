import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
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
        Pattern pattern = Pattern.compile("void ([^\\s$<]+)\\(\\) -> ([^\\s]+)");
        try (BufferedReader r = Files.newBufferedReader(proguardMapping.toPath(), StandardCharsets.UTF_8)) {
            r.lines().forEach(line -> {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    mapping.put(matcher.group(1), matcher.group(2));
                }
                if (line.contains("view.Journal ->"))
                    mapping.put("Journal", line.substring(line.length()-2, line.length()-1));
                if (line.contains("view.Quality ->"))
                    mapping.put("Quality", line.substring(line.length()-2, line.length()-1));
                if (line.contains("controller.ClientController ->"))
                    mapping.put("ClientController", line.substring(line.length()-2, line.length()-1));
            });
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
            Pattern pattern = Pattern.compile("onAction=\"(#[^\\s]+)\"");
            r.lines().forEach(line -> {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String onActionCall = matcher.group(1);
                    String method = onActionCall.substring(1);
                    String obfuscatedCall = onActionCall.replace(method, mapping.get(method));
                    line = line.replace(onActionCall, obfuscatedCall);
                }
                try {
                    w.write(line);
                    w.newLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
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
