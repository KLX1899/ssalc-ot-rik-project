# scheduler.py
import asyncio
import yaml
import datetime
import pytz
from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.date import DateTrigger
from joiner import join_class, ClassSpec


def run_async(coro):
    asyncio.run(coro)


def main():
    with open("schedule.yaml", "r", encoding="utf-8") as file:
        data = yaml.safe_load(file)

    tehran_tz = pytz.timezone("Asia/Tehran")
    sched = BlockingScheduler(timezone=tehran_tz)

    now = datetime.datetime.now(tehran_tz)
    day_map = {0: "mon", 1: "tue", 2: "wed", 3: "thu", 4: "fri", 5: "sat", 6: "sun"}
    current_day = day_map[now.weekday()]
    current_time_str = now.strftime("%H:%M")

    for course in data["classes"]:
        course_name   = course["name"]
        start_time    = course["start"]
        end_time      = course["end"]
        days          = course["days"]
        platform      = course.get("platform", "LMS").upper()

        spec = ClassSpec(
            name=course_name,
            start_time=start_time,
            end_time=end_time,
            platform=platform,
        )
        hour, minute = map(int, start_time.split(":"))

        # 1. Catch-up: currently inside the class window
        if current_day in days and start_time <= current_time_str < end_time:
            print(f"⚠️ [CATCH-UP] Inside window for '{course_name}' ({platform})!")
            print(f"    Starting immediately...")
            sched.add_job(
                run_async,
                trigger=DateTrigger(
                    run_date=now + datetime.timedelta(seconds=2),
                    timezone=tehran_tz,
                ),
                args=[join_class(spec, max_wait_minutes=15, headless=False)],
                id=f"{course_name}_immediate",
            )

        # 2. Regular cron jobs
        for day in days:
            job_id = f"{course_name}_{day}_{start_time}"
            sched.add_job(
                run_async,
                trigger=CronTrigger(day_of_week=day, hour=hour, minute=minute),
                args=[join_class(spec, max_wait_minutes=15, headless=False)],
                id=job_id,
                max_instances=1,
            )
            print(f"📌 {course_name} [{platform}] → {day} at {start_time}–{end_time}")

    print("\n📅 Scheduler is running! Leave this terminal open...")
    sched.start()


if __name__ == "__main__":
    main()