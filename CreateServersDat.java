import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CreateServersDat {
    public static void main(String[] args) throws Exception {
        String[] servers = {
            "mc.hypixel.net",
            "play.cubecraft.net",
            "play.wynncraft.com",
            "play.mccisland.net",
            "mc.hypemc.pro:25565",
            "mc.mineblaze.net:25565",
            "mc.masedworld.net:25565",
            "mc.minepeak.org:25565",
            "mc.musteryworld.net:25565",
            "mc.skycave.pro:25565",
            "mc.vimemc.net:25565",
            "mc.dexland.org:25565",
            "go.bigcraft.pro:25565"
        };

        Path serversDat = Paths.get("servers.dat");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeByte(2); // NBT TAG_COMPOUND
        out.writeUTF(""); // empty name
        out.writeByte(9); // TAG_LIST
        out.writeUTF("servers");
        out.writeByte(10); // TAG_COMPOUND for each entry
        out.writeInt(servers.length);

        Random rand = new Random(42);
        for (String s : servers) {
            String ip = s.contains(":") ? s.split(":")[0] : s;
            int port = s.contains(":") ? Integer.parseInt(s.split(":")[1]) : 25565;
            String name = s.replace(":25565", "").replace(".net", "").replace(".com", "").replace(".org", ".pro");
            if (name.contains(".")) name = name.substring(name.lastIndexOf('.') + 1);
            if (name.equals("mc")) name = s.replace(":25565", "").replace("mc.", "").replace(".net", "").replace(".com", "").replace(".org", "").replace(".pro", "");
            name = name.substring(0, 1).toUpperCase() + name.substring(1);

            // TAG_STRING name
            out.writeByte(8); out.writeUTF("name"); out.writeUTF(name);
            // TAG_STRING ip
            out.writeByte(8); out.writeUTF("ip"); out.writeUTF(ip);
            // TAG_INT port
            out.writeInt(25565);
        }

        out.writeByte(0); // TAG_END for list
        out.writeByte(0); // TAG_END for root

        Files.write(serversDat, baos.toByteArray());
        System.out.println("Created clean servers.dat at " + serversDat.toAbsolutePath());
    }
}
