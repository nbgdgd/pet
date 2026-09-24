from ctypes import cast, POINTER
from comtypes import CLSCTX_ALL
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume


def _get_volume():
    device = AudioUtilities.GetSpeakers()
    interface = device._dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return cast(interface, POINTER(IAudioEndpointVolume))


def set_volume(level):
    _get_volume().SetMasterVolumeLevelScalar(max(0.0, min(1.0, level)), None)
    return f"Громкость {int(level * 100)}"


def set_volume_max():
    set_volume(1.0)
    return "Максимальная громкость"


def set_volume_mid():
    set_volume(0.5)
    return "Громкость пятьдесят процентов"


def set_volume_min():
    set_volume(0.1)
    return "Минимальная громкость"


def volume_up():
    vol = _get_volume()
    level = vol.GetMasterVolumeLevelScalar()
    new_level = min(1.0, level + 0.1)
    set_volume(new_level)
    return f"Громкость {int(new_level * 100)}"


def volume_down():
    vol = _get_volume()
    level = vol.GetMasterVolumeLevelScalar()
    new_level = max(0.0, level - 0.1)
    set_volume(new_level)
    return f"Громкость {int(new_level * 100)}"


def volume_10():
    set_volume(0.1)
    return "Громкость десять"


def volume_20():
    set_volume(0.2)
    return "Громкость двадцать"


def volume_30():
    set_volume(0.3)
    return "Громкость тридцать"


def volume_40():
    set_volume(0.4)
    return "Громкость сорок"


def volume_50():
    set_volume(0.5)
    return "Громкость пятьдесят"


def volume_60():
    set_volume(0.6)
    return "Громкость шестьдесят"


def volume_70():
    set_volume(0.7)
    return "Громкость семьдесят"


def volume_80():
    set_volume(0.8)
    return "Громкость восемьдесят"


def volume_90():
    set_volume(0.9)
    return "Громкость девяносто"


def mute_volume():
    _get_volume().SetMute(1, None)
    return "Звук выключен"


def unmute_volume():
    _get_volume().SetMute(0, None)
    return "Звук включён"


def toggle_mute():
    vol = _get_volume()
    if vol.GetMute():
        vol.SetMute(0, None)
        return "Звук включён"
    else:
        vol.SetMute(1, None)
        return "Звук выключен"
