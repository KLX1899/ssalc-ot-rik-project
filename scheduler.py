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

    # Get current day and time to check for mid-class starts
    now = datetime.datetime.now(tehran_tz)
    
    # Map Python's weekday (0=Mon, 6=Sun) to our YAML format
    day_map = {0: 'mon', 1: 'tue', 2: 'wed', 3: 'thu', 4: 'fri', 5: 'sat', 6: 'sun'}
    current_day = day_map[now.weekday()]
    
    # Format time as "HH:MM" (e.g., "16:00")
    current_time_str = now.strftime("%H:%M")

    for course in data["classes"]:
        course_name = course["name"]
        start_time_str = course["start"]
        end_time_str = course["end"]
        days = course["days"]

        spec = ClassSpec(name=course_name, start_time=start_time_str)
        hour, minute = map(int, start_time_str.split(":"))

        # 1. Check if we are CURRENTLY inside this class's time window
        if current_day in days and start_time_str <= current_time_str < end_time_str:
            print(f"⚠️ [CATCH-UP] You are currently inside the window for '{course_name}'!")
            print(f"    Starting it immediately...")
            
            # Schedule it to run exactly NOW using DateTrigger
            sched.add_job(
                run_async,
                trigger=DateTrigger(run_date=now + datetime.timedelta(seconds=2), timezone=tehran_tz),
                args=[join_class(spec, max_wait_minutes=15, poll_seconds=20, headless=False)],
                id=f"{course_name}_immediate",
            )

        # 2. Schedule the regular repeating Cron jobs for the future
        for day in days:
            job_id = f"{course_name}_{day}_{start_time_str}"
            
            sched.add_job(
                run_async,
                trigger=CronTrigger(day_of_week=day, hour=hour, minute=minute),
                args=[join_class(spec, max_wait_minutes=15, poll_seconds=20, headless=False)],
                id=job_id,
                max_instances=1,
            )
            print(f"Registered future schedule: {course_name} on {day} at {start_time_str}")

    print("\n📅 Scheduler is running! Leave this terminal open...")
    sched.start()

if __name__ == "__main__":
    main()