import java.io.*;
import java.nio.file.*;

public class CleanServers {
    public static void main(String[] args) throws Exception {
        Path home = Paths.get(System.getProperty("user.home"));
        Path appdata = home.resolve("AppData/Roaming");
        Path serversDat = appdata.resolve(".minecraft/servers.dat");
        
        if (!Files.exists(serversDat)) {
            System.out.println("servers.dat not found at " + serversDat);
            System.exit(1);
        }
        
        byte[] data = Files.readAllBytes(serversDat);
        String text = new String(data, "UTF-8");
        
        // Check if the hacked server is there
        if (!text.contains("noooxxxasa")) {
            System.out.println("Hacked server not found in servers.dat - already clean");
            // Still need to fix 1.8.json
            createAssetIndex(appdata);
            return;
        }
        
        System.out.println("Found hacked server in servers.dat, removing...");
        
        // The NBT format is complex. Let's just copy the file without the bad entry.
        // We'll write a clean servers.dat with the same servers minus the bad one.
        // Actually, let's use a different approach: binary patch.
        // Find the pattern "noooxxxasa" and remove the surrounding NBT entry.
        
        // For simplicity, let's read the NBT structure properly.
        // The servers.dat format:
        // TAG_Compound("") 
        //   TAG_List("servers")
        //     entries...
        
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bis);
        
        // Read TAG_Compound("")
        byte tagType = dis.readByte();
        String name = dis.readUTF(); // ""
        
        // Read TAG_List("servers") with TAG_Compound entries
        byte listTag = dis.readByte(); // TAG_LIST
        String listName = dis.readUTF(); // "servers"
        byte entryTag = dis.readByte(); // TAG_COMPOUND (10)
        int entryCount = dis.readInt();
        
        System.out.println("Root: " + tagType + " '" + name + "'");
        System.out.println("List: " + listName + " with " + entryCount + " entries");
        
        // Now scan through entries to find and skip the bad one
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        
        dos.writeByte(10); // TAG_COMPOUND
        dos.writeUTF("");  // ""
        dos.writeByte(9);  // TAG_LIST
        dos.writeUTF("servers");
        dos.writeByte(10); // TAG_COMPOUND per entry
        
        int cleaned = 0;
        for (int i = 0; i < entryCount; i++) {
            // Read one entry
            ByteArrayOutputStream entryBuf = new ByteArrayOutputStream();
            DataOutputStream entryOut = new DataOutputStream(entryBuf);
            
            String ip = "";
            while (true) {
                byte t = dis.readByte();
                if (t == 0) break; // TAG_END
                String key = dis.readUTF();
                if (t == 8) { // TAG_STRING
                    String val = dis.readUTF();
                    if (key.equals("ip")) ip = val;
                    entryOut.writeByte(t); entryOut.writeUTF(key); entryOut.writeUTF(val);
                } else if (t == 1) { // TAG_BYTE
                    byte val = dis.readByte();
                    entryOut.writeByte(t); entryOut.writeUTF(key); entryOut.writeByte(val);
                } else if (t == 3) { // TAG_INT
                    int val = dis.readInt();
                    entryOut.writeByte(t); entryOut.writeUTF(key); entryOut.writeInt(val);
                }
            }
            entryOut.writeByte(0); // TAG_END
            
            if (ip.contains("noooxxxasa")) {
                System.out.println("Removed server: " + ip);
            } else {
                dos.write(entryBuf.toByteArray());
                cleaned++;
            }
        }
        
        dos.writeByte(0); // TAG_END for list
        dos.writeByte(0); // TAG_END for root
        
        // Need to fix the entry count at position
        byte[] newData = baos.toByteArray();
        // Fix entry count at offset: TAG_COMPOUND(1) + UTF+""(2) + TAG_LIST(1) + UTF+"servers"(9) + TAG_COMPOUND(1) = 14 bytes
        newData[13] = (byte)((cleaned >> 24) & 0xFF);
        newData[14] = (byte)((cleaned >> 16) & 0xFF);
        newData[15] = (byte)((cleaned >> 8) & 0xFF);
        newData[16] = (byte)(cleaned & 0xFF);
        
        // Backup original
        Files.copy(serversDat, serversDat.resolveSibling("servers.dat.backup"), StandardCopyOption.REPLACE_EXISTING);
        Files.write(serversDat, newData);
        System.out.println("Cleaned servers.dat written. Removed 1 entry, kept " + cleaned + " servers.");
        System.out.println("Backup saved as servers.dat.backup");
        
        // Now fix 1.8.json
        createAssetIndex(appdata);
    }
    
    static void createAssetIndex(Path appdata) throws Exception {
        Path indexesDir = appdata.resolve(".minecraft/assets/indexes");
        Files.createDirectories(indexesDir);
        Path indexFile = indexesDir.resolve("1.8.json");
        String json = "{\"objects\":{}}";
        Files.write(indexFile, json.getBytes());
        System.out.println("Created " + indexFile);
    }
}
