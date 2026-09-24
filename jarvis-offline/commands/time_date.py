from datetime import datetime


def say_time():
    now = datetime.now().strftime("%H:%M")
    return f"Сейчас {now}"


def say_date():
    today = datetime.now().strftime("%d %B %Y")
    return f"Сегодня {today}"


def say_day_of_week():
    days = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]
    now = datetime.now()
    return f"Сегодня {days[now.weekday()]}, {now.strftime('%d %B %Y')}"
