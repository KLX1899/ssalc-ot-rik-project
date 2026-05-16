# scheduler.py
import asyncio
import yaml
import datetime
import pytz
from pathlib import Path
from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.date import DateTrigger
from joiner import join_class, ClassSpec
from login_setup import do_login_lms
from login_setup_nima import do_login_nima

STATE_PATH_LMS  = Path("state.json")
STATE_PATH_NIMA = Path("state_nima.json")


def run_async(coro):
    asyncio.run(coro)


def _needs_login(state_path: Path) -> bool:
    return not state_path.exists()


async def _bootstrap_logins(classes: list) -> None:
    """
    Called once at startup. For each platform (LMS / NIMA) that is used
    by at least one class, ensures a valid state file exists.
    Runs login automatically if the state file is missing.
    """
    needs_lms  = any(c.get("platform", "LMS").upper() == "LMS"  for c in classes)
    needs_nima = any(c.get("platform", "LMS").upper() == "NIMA" for c in classes)

    if needs_lms and _needs_login(STATE_PATH_LMS):
        print("🔑 [Startup] LMS state file missing — logging in to LMS...")
        await do_login_lms(headless=False)
        print("✅ [Startup] LMS login complete.")

    if needs_nima and _needs_login(STATE_PATH_NIMA):
        print("🔑 [Startup] NIMA state file missing — logging in to NIMA...")
        await do_login_nima(headless=False)
        print("✅ [Startup] NIMA login complete.")


def main():
    with open("schedule.yaml", "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)

    classes = data["classes"]

    # ── Startup: make sure we are logged in to every required platform ────────
    asyncio.run(_bootstrap_logins(classes))

    # ── Build the scheduler ───────────────────────────────────────────────────
    tehran_tz = pytz.timezone("Asia/Tehran")
    sched     = BlockingScheduler(timezone=tehran_tz)

    now = datetime.datetime.now(tehran_tz)
    day_map = {
        0: "mon", 1: "tue", 2: "wed", 3: "thu",
        4: "fri", 5: "sat", 6: "sun",
    }
    current_day      = day_map[now.weekday()]
    current_time_str = now.strftime("%H:%M")

    for course in classes:
        course_name = course["name"]
        start_time  = course["start"]
        end_time    = course["end"]
        days        = course["days"]
        platform    = course.get("platform", "LMS").upper()

        spec = ClassSpec(
            name=course_name,
            start_time=start_time,
            end_time=end_time,
            platform=platform,
        )
        hour, minute = map(int, start_time.split(":"))

        # ── Catch-up: currently inside this class's window ────────────────────
        if current_day in days and start_time <= current_time_str < end_time:
            print(
                f"⚠️ [CATCH-UP] Inside window for '{course_name}' [{platform}]! "
                f"Joining immediately..."
            )
            sched.add_job(
                run_async,
                trigger=DateTrigger(
                    run_date=now + datetime.timedelta(seconds=2),
                    timezone=tehran_tz,
                ),
                args=[join_class(spec, max_wait_minutes=15, headless=False)],
                id=f"{course_name}_immediate",
            )

        # ── Regular cron jobs ─────────────────────────────────────────────────
        for day in days:
            job_id = f"{course_name}_{day}_{start_time}"
            sched.add_job(
                run_async,
                trigger=CronTrigger(
                    day_of_week=day, hour=hour, minute=minute
                ),
                args=[join_class(spec, max_wait_minutes=15, headless=False)],
                id=job_id,
                max_instances=1,
            )
            print(
                f"📌 Registered: {course_name} [{platform}] "
                f"→ {day} at {start_time}–{end_time}"
            )

    print("\n📅 Scheduler running — leave this terminal open.")
    sched.start()


if __name__ == "__main__":
    main()