package com.aniblaze.tokyoghoul.config;

import java.io.*;
import java.util.Properties;

public class ModConfig {
    public static final String VERSION = "1.0.0";

    private static final Properties props = new Properties();
    private static File configFile;

    public static int GHUL_SPAWN_CHANCE = 15;
    public static int MAX_RC = 1_000_000;
    public static int MAX_BLOOD = 1000;
    public static int BLOOD_PER_SPEAR = 40;
    public static int BLOOD_PER_BLADE = 20;
    public static int BLOOD_PER_EXPLOSION = 100;
    public static int BLOOD_PER_DASH = 30;
    public static double RC_LOST_ON_DEATH = 0.15;
    public static double HUNGER_THRESHOLD = 0.10;
    public static double KAKUGAN_SPEED_BONUS = 0.20;
    public static double KAKUGAN_DAMAGE_BONUS = 0.20;
    public static int STAGE_2_RC = 10_000;
    public static int STAGE_3_RC = 50_000;
    public static int STAGE_4_RC = 250_000;
    public static int KAKUJA_RC = 1_000_000;
    public static int BLOOD_SPEAR_COOLDOWN = 40;
    public static int BLOOD_BLADE_COOLDOWN = 100;
    public static int BLOOD_EXPLOSION_COOLDOWN = 200;
    public static int BLOOD_DASH_COOLDOWN = 60;
    public static int BLOOD_BLADE_DURATION = 600;

    public static void init() {
        configFile = new File("config/tokyoghoul.properties");
        if (!configFile.exists()) {
            saveDefault();
        }
        load();
    }

    private static void load() {
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
            GHUL_SPAWN_CHANCE = getInt("ghoul_spawn_chance", 15);
            MAX_RC = getInt("max_rc", 1_000_000);
            MAX_BLOOD = getInt("max_blood", 1000);
            RC_LOST_ON_DEATH = getDouble("rc_lost_on_death", 0.15);
            KAKUGAN_SPEED_BONUS = getDouble("kakugan_speed_bonus", 0.20);
            KAKUGAN_DAMAGE_BONUS = getDouble("kakugan_damage_bonus", 0.20);
            STAGE_2_RC = getInt("stage_2_rc", 10_000);
            STAGE_3_RC = getInt("stage_3_rc", 50_000);
            STAGE_4_RC = getInt("stage_4_rc", 250_000);
            KAKUJA_RC = getInt("kakuja_rc", 1_000_000);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void saveDefault() {
        configFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            props.setProperty("ghoul_spawn_chance", "15");
            props.setProperty("max_rc", "1000000");
            props.setProperty("max_blood", "1000");
            props.setProperty("rc_lost_on_death", "0.15");
            props.setProperty("kakugan_speed_bonus", "0.20");
            props.setProperty("kakugan_damage_bonus", "0.20");
            props.setProperty("stage_2_rc", "10000");
            props.setProperty("stage_3_rc", "50000");
            props.setProperty("stage_4_rc", "250000");
            props.setProperty("kakuja_rc", "1000000");
            props.store(fos, "Tokyo Ghoul Mod Configuration");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int getInt(String key, int def) {
        try { return Integer.parseInt(props.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private static double getDouble(String key, double def) {
        try { return Double.parseDouble(props.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public static double getBloodCostForStages() {
        return MAX_BLOOD;
    }
}
