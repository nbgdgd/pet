import type { Plugin } from "@opencode-ai/plugin";
import { SpeechSynthesizer } from "system.speech";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

// Конфигурация голосового ассистента
interface JarvisConfig {
  enabled: boolean;
  voice: string; // en-US-GuyNeural / ru-RU-DmitryNeural
}

// Сохранение конфигурации
function saveConfig(config: JarvisConfig): void {
  const configDir = path.join(process.env.USERPROFILE || "", ".opencode-jarvis");
  const configPath = path.join(configDir, "config.json");
  
  if (!fs.existsSync(configDir)) {
    fs.mkdirSync(configDir, { recursive: true });
  }
  
  fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
}

// Загрузка конфигурации
function loadConfig(): JarvisConfig {
  const configPath = path.join(process.env.USERPROFILE || "", ".opencode-jarvis", "config.json");
  
  try {
    if (fs.existsSync(configPath)) {
      const content = fs.readFileSync(configPath, "utf8");
      const config = JSON.parse(content);
      return {
        enabled: config.enabled !== false,
        voice: config.voice || "en-GB-RyanNeural"
      };
    }
  } catch (error) {
    console.error("Не удалось загрузить конфигурацию JARVIS:", error);
  }
  
  // Конфиг по умолчанию
  const defaultConfig: JarvisConfig = {
    enabled: true,
    voice: "en-GB-RyanNeural"
  };
  
  saveConfig(defaultConfig);
  return defaultConfig;
}

// Очередь воспроизведения TTS
class TTSQueue {
  private queue: { text: string; callback: () => void }[] = [];
  private isPlaying = false;
  private currentProcess?: { kill: () => void };

  async enqueue(text: string): Promise<void> {
    return new Promise((resolve) => {
      this.queue.push({ text, callback: resolve });
      this.processQueue();
    });
  }

  private async processQueue(): Promise<void> {
    if (this.isPlaying || this.queue.length === 0) return;

    const item = this.queue.shift()!;
    this.isPlaying = true;

    try {
      // Используем edge-tts с fallback на SAPI
      await this.speakWithFallback(item.text);
    } catch (error) {
      console.error("Ошибка при озвучке:", error);
    } finally {
      item.callback();
      this.isPlaying = false;
      this.processQueue();
    }
  }

  private async speakWithFallback(text: string): Promise<void> {
    try {
      // Попробовать edge-tts
      await this.speakWithEdgeTTS(text);
    } catch (error) {
      console.warn("edge-tts недоступен, используем SAPI:", error);
      await this.speakWithSAPI(text);
    }
  }

  private async speakWithEdgeTTS(text: string): Promise<void> {
    const { default: edgeTTS } = await import("edge-tts");
    
    // Генерация речи с помощью edge-tts
    const voice = loadConfig().voice;
    const outputFile = path.join(
      process.env.TEMP || "C:\\Windows\\Temp",
      `jarvis-${Date.now()}.mp3`
    );

    const ttsStream = edgeTTS.createStream(text, { voice });
    const writer = fs.createWriteStream(outputFile);
    ttsStream.pipe(writer);

    await new Promise((resolve, reject) => {
      writer.on("finish", resolve);
      writer.on("error", reject);
      ttsStream.on("error", reject);
    });

    // Проигрывание через PowerShell
    await this.playAudioPowerShell(outputFile);

    // Удаление временного файла
    try {
      fs.unlinkSync(outputFile);
    } catch (e) {
      console.warn("Не удалось удалить временный файл:", e);
    }
  }

  private async speakWithSAPI(text: string): Promise<void> {
    return new Promise((resolve) => {
      const synth = new SpeechSynthesizer();
      synth.speak(text);
      synth.on("speechComplete", () => resolve());
    });
  }

  private async playAudioPowerShell(filePath: string): Promise<void> {
    // PowerShell скрипт для проигрывания аудио
    const psScript = `
Add-Type -AssemblyName PresentationCore;
$player = [System.Windows.Media.MediaPlayer]::new();
$player.Open([System.Uri]::new('file:///${filePath.replace(/\\/g, '/')}'));
$player.PlaySync();
`;

    const tempPsScript = path.join(process.env.TEMP || "C:\\Windows\\Temp", `play-${Date.now()}.ps1`);
    fs.writeFileSync(tempPsScript, psScript);

    await new Promise((resolve) => {
      const { exec } = require("child_process");
      exec(`powershell -ExecutionPolicy Bypass -File "${tempPsScript}"`, (error: Error) => {
        if (error) console.warn("Ошибка при воспроизведении:", error);
        resolve(undefined);
      });
    });

    try {
      fs.unlinkSync(tempPsScript);
    } catch (e) {
      console.warn("Не удалось удалить временный скрипт PowerShell:", e);
    }
  }
}

// Основной плагин JARVIS
const jarvisPlugin: Plugin = async ({ client, project, directory, $ }) => {
  const ttsQueue = new TTSQueue();
  let lastAssistantMessage = "";

  // Загрузка конфигурации
  let config = loadConfig();

  return {
    config: (cfg) => {
      // Подписка на изменения конфигурации
    },

    "tool.execute.before": async ({ event }) => {
      if (!config.enabled) return;

      const toolName = event.tool?.name || event.tool?.title || "неизвестный инструмент";
      const toolTitle = event.tool?.title || toolName;

      await ttsQueue.enqueue(`Начинаю ${toolTitle}`);
    },

    "tool.execute.after": async ({ event }) => {
      if (!config.enabled) return;

      const toolName = event.tool?.name || event.tool?.title || "неизвестный инструмент";
      const toolTitle = event.tool?.title || toolName;

      // Получение краткого заголовка результата
      let resultText = "Инструмент завершён";
      if (event.result?.success === false) {
        const error = event.result?.error || "с ошибкой";
        resultText = `Инструмент ${toolTitle} завершился ${error}`;
      } else if (event.result?.status) {
        resultText = `Инструмент ${toolTitle} ${event.result.status}`;
      }

      await ttsQueue.enqueue(resultText);
    },

    "session.idle": async ({ event }) => {
      if (!config.enabled) return;

      try {
        // Получение последнего сообщения ассистента
        const messages = await client.messages({ limit: 1 });
        if (messages.length > 0) {
          lastAssistantMessage = messages[0].content;
        }

        if (lastAssistantMessage) {
          // Краткое резюме
          let summary = lastAssistantMessage;
          
          // Удаляем технические детали и обрезаем до 2-3 предложений
          summary = summary.replace(/```[\s\S]*?```/g, ""); // Удаляем кодовые блоки
          summary = summary.replace(/\*/g, ""); // Удаляем выделение
          summary = summary.replace(/\n+/g, " "); // Удаляем переносы
          summary = summary.trim();

          // Обрезаем до 2-3 предложений
          const sentences = summary.split(/[.!?]+/);
          if (sentences.length > 3) {
            summary = sentences.slice(0, 3).join(". ") + ".";
          }

          if (summary.length > 200) {
            summary = summary.substring(0, 200) + "...";
          }

          await ttsQueue.enqueue(`Задача завершена. ${summary}`);
        }
      } catch (error) {
        console.error("Ошибка при обработке session.idle:", error);
        await ttsQueue.enqueue("Задача завершена");
      }
    },

    // Хук для изменения конфигурации
    config: (cfg) => {
      config = loadConfig(); // Перезагружаем конфиг при изменениях
    }
  };
};

export default jarvisPlugin;