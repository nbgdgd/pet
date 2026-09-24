import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class CreateAssetIndex {
    public static void main(String[] args) throws Exception {
        String json = "{\"objects\":{}}";
        Path out = Paths.get("assets/indexes/1.8.json");
        Files.createDirectories(out.getParent());
        Files.write(out, json.getBytes());
        System.out.println("Created " + out.toAbsolutePath());
    }
}
