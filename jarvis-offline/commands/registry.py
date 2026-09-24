from commands import apps, volume, time_date, system

# Короткие алиасы и фонетические варианты добавлены к каждому интенту
# для устойчивости к неточному распознаванию речи.
COMMANDS = {
    # === ПРОВОДНИК ===
    ("закрой проводник", "закрой explorer", "выключи проводник", "заверши проводник",
     "close explorer", "close file explorer",
     # Короткие / неточные
     "close", "explorer close", "close explorer now",
     "проводник закрой", "проводник выключи"):
    apps.close_explorer,

    ("открой проводник", "мои файлы", "файловый менеджер",
     "open explorer", "open file explorer",
     "open", "explorer open", "покажи файлы", "открой папки", "где файлы"):
    apps.open_explorer,

    # === БРАУЗЕРЫ ===
    ("открой браузер", "запусти браузер", "браузер", "открой интернет", "интернет",
     "open browser", "launch browser",
     "browser", "веб", "открой веб", "инет"):
    apps.open_browser,
    ("открой firefox", "firefox", "запусти firefox", "огненный лис", "лиса",
     "open firefox", "launch firefox",
     "ff", "fox", "мозилла", "mozilla", "filefox", "фаерфокс"):
    apps.open_firefox,
    ('открой chrome', 'chrome', 'хром', 'запусти chrome', 'открой хром', 'гугл хром', 'open chrome', 'launch chrome', 'google chrome', 'гугл'):    apps.open_chrome,
    ("открой edge", "edge", "запусти edge", "майкрософт эдж",
     "open edge", "launch edge",
     "эдж", "ms edge", "microsoft edge"):
    apps.open_edge,

    # === ЗАКРЫТЬ ПРИЛОЖЕНИЯ ===
    ("закрой телеграм", "закрой телеграмм", "выключи телеграм", "telegram close",
     "close telegram", "заверши телеграм"):
    apps.close_telegram,
    ("закрой дискорд", "выключи дискорд", "discord close",
     "close discord", "заверши дискорд"):
    apps.close_discord,
    ("закрой спотифай", "выключи спотифай", "spotify close",
     "close spotify"):
    apps.close_spotify,
    ("закрой firefox", "закрой фаерфокс", "закрой огненного лиса",
     "close firefox", "выключи firefox"):
    apps.close_firefox,
    ("закрой chrome", "закрой хром", "выключи хром",
     "close chrome", "выключи chrome"):
    apps.close_chrome,
    ("закрой edge", "закрой майкрософт эдж", "выключи edge",
     "close edge", "выключи эдж"):
    apps.close_edge,
    ("закрой блокнот", "выключи блокнот", "close notepad",
     "закрой notepad"):
    apps.close_notepad,
    ("закрой cmd", "закрой командную строку", "закрой консоль",
     "close cmd", "close command prompt", "выключи cmd",
     "закрой терминал"):
    apps.close_cmd,
    ("закрой powershell", "выключи powershell", "close powershell"):
    apps.close_powershell,
    ("закрой vs code", "закрой вижуал студио код", "close vscode",
     "закрой vscode", "close visual studio code"):
    apps.close_vscode,
    ("закрой калькулятор", "выключи калькулятор", "close calc",
     "close calculator"):
    apps.close_calculator,

    # === RAM ===
    ("освободи ram", "освободи оперативную память", "очисти ram",
     "free memory", "clear ram", "сбрось ram", "сбрось память",
     "освободи память", "free ram"):
    apps.free_ram,

    # === ПРИЛОЖЕНИЯ ===
    ("открой блокнот", "блокнот", "notepad", "запусти блокнот",
     "open notepad",
     "note", "нотепад"):
    apps.open_notepad,
    ('открой калькулятор', 'калькулятор', 'calc', 'запусти калькулятор', 'open calculator', 'кальк'):    apps.open_calculator,
    ("командная строка", "открой командную строку", "cmd", "терминал",
     "open cmd", "open command prompt",
     "консоль", "команда"):
    apps.open_cmd,
    ("открой powershell", "powershell", "запусти powershell",
     "open powershell",
     "ps", "пауэршелл"):
    apps.open_powershell,
    ("диспетчер задач", "открой диспетчер задач", "task manager",
     "open task manager",
     "диспетчер", "задачи"):
    apps.open_task_manager,
    ("открой настройки", "настройки", "параметры", "системные настройки",
     "open settings",
     "settings", "setting"):
    apps.open_settings,
    ('панель управления', 'открой панель управления', 'open control panel', 'control panel'):
    apps.open_control_panel,

    # === ПАПКИ ===
    ("открой загрузки", "загрузки", "папка загрузки",
     "open downloads"): apps.open_downloads,
    ("открой документы", "документы", "мои документы",
     "open documents"): apps.open_documents,
    ("открой папку рабочий стол", "открой рабочий стол", "рабочий стол",
     "open desktop"): apps.open_desktop_folder,
    ("открой изображения", "картинки", "открой картинки",
     "open pictures"): apps.open_pictures,
    ("открой музыку", "музыка", "открой музыку папку", "моя музыка",
     "open music"): apps.open_music,
    ("открой видео", "видео", "мои видео",
     "open videos"): apps.open_videos,

    # === ЭКРАН ===
    ("покажи рабочий стол", "сверни все окна", "показать рабочий стол", "свернуть все окна",
     "show desktop"): apps.show_desktop,
    ("окно выполнить", "открой выполнить", "выполнить", "run",
     "open run"): apps.open_run_dialog,
    ("открой диспетчер устройств", "диспетчер устройств",
     "open device manager"): apps.open_device_manager,
    ("открой дисковую утилиту", "дефрагментация", "дефрагментация диска"): apps.open_defragment,
    ("очистка диска", "открой очистку диска", "диск клинер",
     "open disk cleanup"): apps.open_disk_cleanup,

    # === ЗВУК ===
    ("выключи звук", "без звука", "mute", "отключи звук", "замьють", "заглуши звук",
     "mute volume", "тихо"):
    volume.mute_volume,
    ("включи звук обратно", "верни звук обратно", "unmute", "включи аудио", "верни громкость", "включи звук",
     "unmute volume", "звук включи"):
    volume.unmute_volume,
    ("сделай громче", "прибавь громкость", "увеличь громкость", "громче",
     "volume up", "громкость прибавь", "громко"):
    volume.volume_up,
    ("сделай тише", "убавь громкость", "уменьши громкость", "убавь звук", "уменьши звук", "тише",
     "volume down", "громкость убавь"):
    volume.volume_down,
    ("максимальная громкость", "на полную", "макс громкость",
     "max volume"): volume.set_volume_max,
    ("минимальная громкость", "минимум громкости"): volume.set_volume_min,
    ("средняя громкость", "половина громкости", "громкость 50"): volume.set_volume_mid,
    ("громкость 10", "громкость десять", "10 процентов"): volume.volume_10,
    ("громкость 20", "громкость двадцать", "20 процентов"): volume.volume_20,
    ("громкость 30", "громкость тридцать", "30 процентов"): volume.volume_30,
    ("громкость 40", "громкость сорок", "40 процентов"): volume.volume_40,
    ("громкость 60", "громкость шестьдесят", "60 процентов"): volume.volume_60,
    ("громкость 70", "громкость семьдесят", "70 процентов"): volume.volume_70,
    ("громкость 80", "громкость восемьдесят", "80 процентов"): volume.volume_80,
    ("громкость 90", "громкость девяносто", "90 процентов"): volume.volume_90,

    # === СКОЛЬКО ДНЕЙ ===
    ("сколько дней до", "сколько дней осталось", "дней до", "дней осталось",
     "days until", "how many days until"):
    system.days_until,

    # === ИГРОВОЙ РЕЖИМ ===
    ("игровой режим", "режим игры", "игра", "запусти игру", "гейминг",
     "game mode", "gaming mode", "gaming",
     "почисти процессы", "убери лишние процессы"):
    system.gaming_mode,

    # === ВРЕМЯ ===
    ("который час", "скажи время", "текущее время", "сколько времени", "часы",
     "what time is it", "tell time", "current time",
     "время", "time", "сколько час"):
    time_date.say_time,
    ("какая дата", "какое сегодня число", "сегодняшняя дата", "число сегодня", "какой сегодня день",
     "what is the date", "todays date",
     "дата", "date"):
    time_date.say_date,
    ("что за день", "какой сегодня праздник", "день недели", "какой сегодня день недели",
     "what day is it"):
    time_date.say_day_of_week,

    # === СИСТЕМА ===
    ('заблокируй экран', 'заблокируй компьютер', 'блокировка', 'lock screen', 'lock pc', 'lock computer', 'блокировка экрана', 'заблокируй'):    system.lock_pc,
    ("характеристики компьютера", "информация о системе", "информация о пк", "спецификация",
     "system info", "pc specs",
     "характеристики пк", "спецификация пк", "спека"):
    system.get_system_info,
    ('загрузка процессора', 'загрузка cpu', 'сколько процентов процессор', 'cpu usage', 'процессор загружен'):    system.get_cpu_usage,
    ('использование оперативной памяти', 'загрузка ram', 'сколько занято оперативной памяти', 'ram usage', 'оперативка занята'):    system.get_ram_usage,
    ('свободное место на диске', 'место на диске', 'сколько места на диске', 'память на диске', 'disk space'):    system.get_disk_usage,
    ("выключи компьютер", "выключи пк", "отключи компьютер", "заверши работу",
     "shutdown", "shut down", "turn off pc",
     "shutdown pc", "выключи машину", "турп оф"):
    system.shutdown_pc,
    ("отмени выключение", "отмена выключения", "не выключай",
     "cancel shutdown",
     "отмени shutdown", "не надо выключать"):
    system.cancel_shutdown,
    ("перезагрузи компьютер", "перезагрузи", "перезагрузка", "рестарт", "перезагрузка пк",
     "restart", "reboot",
     "restart pc", "reboot pc", "компьютер перезагрузи", "перезагрузить"):
    system.restart_pc,
    ("спящий режим", "отправь в сон", "сон", "sleep",
     "sleep mode", "режим сна"):
    system.sleep_pc,
    ("гибернация", "глубокий сон", "hibernate"):
    system.hibernate_pc,
    ('выйти из системы', 'разлогинься', 'log off', 'сменить пользователя', 'сменить юзера'):    system.log_off,

    # === MESSENGERS ===
    ("открой телеграм", "телеграм", "телеграмм", "telegram", "запусти телеграм",
     "open telegram", "launch telegram",
     "tg", "телега", "телеграма"):
    apps.open_telegram,
    ("открой дискорд", "дискорд", "discord", "запусти дискорд",
     "open discord", "launch discord",
     "disc", "дис"):
    apps.open_discord,

    # === МУЛЬТИМЕДИА ===
    ("открой спотифай", "спотифай", "spotify", "музыкальный плеер",
     "open spotify", "play music",
     "споти", "spoti"):
    apps.open_spotify,

    # === OPENCODE ===
    ("открой опенкод", "опенкод", "opencode", "запусти опенкод",
     "open opencode", "launch opencode",
     "опен код", "опэнкод", "апэнкод"):
    apps.open_opencode,

    # === РАЗРАБОТКА ===
    ("открой vs code", "vs code", "вижуал студио код", "запусти vs code",
     "open visual studio code", "open vscode",
     "vscode", "вижуал студио"):
    apps.open_vscode,

    # === СКРИНШОТ ===
    ('сделай скриншот', 'скриншот', 'снимок экрана', 'скрин', 'фото экрана', 'screenshot', 'take screenshot', 'ss'):    apps.screenshot,
    ("открой ножницы", "нажать ножницы", "сниппинг тул", "snipping tool",
     "snip"):
    apps.open_snipping_tool,

    # === КОРЗИНА ===
    ('открой корзину', 'корзина', 'мусорка', 'open recycle bin', 'recycle bin', 'bin'):    apps.open_recycle_bin,
    ('очисти корзину', 'опустоши корзину', 'empty recycle bin', 'empty bin'):    apps.empty_recycle_bin,

    # === СЕТЬ ===
    ("открой сетевые настройки", "настройки сети", "сеть",
     "network settings", "сетевые настройки"):
    apps.open_network_settings,
    ("открой настройки вайфай", "вайфай", "wi-fi", "настройки wifi",
     "wifi settings", "wifi"):
    apps.open_wifi_settings,
    ("открой блютуз", "блютуз", "bluetooth", "настройки блютуз",
     "bluetooth settings", "bt"):
    apps.open_bluetooth,

    # === ДИСПЛЕЙ И ЗВУК ===
    ("открой звуковые настройки", "настройки звука", "открой настройки звука",
     "sound settings"): apps.open_sound_settings,

    # === СЛУЖЕБНЫЕ ===
    ("открой планировщик заданий", "планировщик заданий", "tasks"): apps.open_task_scheduler,
    ("открой службы", "службы", "services"): apps.open_services,
    ("открой реестр", "реестр", "regedit", "редактор реестра"): apps.open_registry_editor,
    ("переменные среды", "переменные окружения"): apps.open_environment_variables,
    ("о системе", "о компьютере"): apps.open_about_pc,

    # === НАСТРОЙКИ СИСТЕМЫ ===
    ("язык и регион", "региональные настройки", "язык"): apps.open_region_language,
    ("настройки клавиатуры", "клавиатура", "раскладка"): apps.open_keyboard_settings,
    ("настройки мыши", "мышь"): apps.open_mouse_settings,
    ("дата и время", "настройки даты"): apps.open_date_time_settings,
    ("настройки питания", "электропитание", "батарея"): apps.open_power_settings,
    ("настройки хранилища", "хранилище", "контроль памяти"): apps.open_storage_settings,
    ("приложения и возможности", "программы", "удаление программ"): apps.open_apps_settings,
    # "гейминг" убран: он же стоял у system.gaming_mode, а _alias_map
    # хранит один вариант на действие — побеждало то, что описано ниже
    # по файлу, и "гейминг" открывал окно параметров вместо игрового режима
    ("игровые настройки", "настройки игр", "параметры игр"): apps.open_gaming_settings,
    ("конфиденциальность", "приватность", "настройки приватности"): apps.open_privacy_settings,
    ("центр обновления", "обновления", "windows update", "обновить систему"): apps.open_update_settings,
    ("активация", "активация windows", "лицензия"): apps.open_activation_settings,
    ("поиск неисправностей", "устранение неполадок", "диагностика"): apps.open_troubleshoot,
    ("многозадачность", "настройки многозадачности"): apps.open_multitasking,
    ("панель задач", "настройки панели задач", "таскбар"): apps.open_taskbar_settings,
    ("уведомления", "настройки уведомлений"): apps.open_notification_settings,
}
