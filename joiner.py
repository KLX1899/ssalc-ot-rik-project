# joiner.py
import asyncio
import time
from dataclasses import dataclass
from pathlib import Path
from playwright.async_api import async_playwright, TimeoutError as PWTimeoutError

STATE_PATH = Path("state.json")
DASHBOARD_URL = "https://lmshome.aut.ac.ir/panel/home"

# How long to wait between refreshes when class hasn't started yet (seconds)
NOT_STARTED_REFRESH_INTERVAL = 60  # 1 minute


@dataclass(frozen=True)
class ClassSpec:
    name: str
    start_time: str  # e.g., "15:00"
    end_time: str = ""  # e.g., "16:30" — used to know when to close the browser


async def _try_find_join_button(page, spec: ClassSpec):
    """
    Looks at the current course page and returns one of three statuses:
      - ("found",       join_button_locator)  -> button is visible and clickable
      - ("not_started", None)                 -> row exists but shows "هنوز برگزار نشده"
      - ("missing",     None)                 -> row / button not found at all
    """
    try:
        row = page.locator("tr").filter(has_text=spec.start_time).first

        row_count = await row.count()
        if row_count == 0:
            return ("missing", None)

        # Check for "هنوز برگزار نشده" anywhere inside that row
        not_started_locator = row.locator("text=هنوز برگزار نشده")
        not_started_count = await not_started_locator.count()
        if not_started_count > 0:
            return ("not_started", None)

        # Try to find the actual join button inside the row
        join_button = row.locator("button", has_text="ورود به جلسه بیگبلوباتن").first
        btn_count = await join_button.count()
        if btn_count == 0:
            return ("missing", None)

        is_visible = await join_button.is_visible()
        is_enabled = await join_button.is_enabled()
        if is_visible and is_enabled:
            return ("found", join_button)
        else:
            return ("not_started", None)  # present but not clickable → treat same way

    except Exception as e:
        print(f"[{spec.name}] _try_find_join_button error: {e}")
        return ("missing", None)


async def _navigate_to_course(page, spec: ClassSpec) -> bool:
    """
    From the dashboard, find and click the course link.
    Returns True on success, False on failure.
    """
    try:
        await page.goto(DASHBOARD_URL)
        await page.locator("a.course-link").first.wait_for(state="visible", timeout=15_000)

        course_link = (
            page.locator("a.course-link")
            .filter(has_text=spec.name)
            .first
        )
        await course_link.wait_for(state="visible", timeout=10_000)
        await course_link.click()
        print(f"[{spec.name}] Navigated to course page.")
        await asyncio.sleep(3)
        return True
    except Exception as e:
        print(f"[{spec.name}] Could not navigate to course page: {e}")
        return False


async def _keep_alive_until_class_ends(current_page, spec: ClassSpec, browser):
    """
    After successfully joining, keep the browser open.

    Strategy (in order):
      1. If end_time is known, sleep until that wall-clock time + a 5-min buffer.
      2. Otherwise, wait for the BBB page to close/navigate away on its own.
      3. As a last resort, just wait forever (until the user closes the terminal).
    """
    import datetime, pytz

    tehran_tz = pytz.timezone("Asia/Tehran")

    if spec.end_time:
        now = datetime.datetime.now(tehran_tz)
        end_h, end_m = map(int, spec.end_time.split(":"))
        end_dt = now.replace(hour=end_h, minute=end_m, second=0, microsecond=0)

        # If the end time is already past today (shouldn't happen, but just in case)
        if end_dt < now:
            end_dt += datetime.timedelta(days=1)

        # Add a 5-minute buffer so we don't drop out right at the bell
        end_dt += datetime.timedelta(minutes=5)

        wait_seconds = (end_dt - datetime.datetime.now(tehran_tz)).total_seconds()
        print(
            f"[{spec.name}] 🎓 Staying in class until {end_dt.strftime('%H:%M')} "
            f"({int(wait_seconds // 60)} min from now)..."
        )
        await asyncio.sleep(max(wait_seconds, 0))
        print(f"[{spec.name}] ⏰ Class time is over. Closing browser.")

    else:
        # No end_time known — wait until the page itself closes or navigates away
        print(
            f"[{spec.name}] 🎓 Joined successfully. "
            f"No end_time configured — keeping browser open until the page closes..."
        )
        try:
            # wait_for_event("close") resolves when the page (tab) is closed
            await current_page.wait_for_event("close", timeout=0)  # timeout=0 → no timeout
            print(f"[{spec.name}] Page was closed. Done.")
        except Exception:
            # If the page object disappears for any other reason, just return
            pass


async def join_class(
    spec: ClassSpec,
    *,
    max_wait_minutes: int = 15,
    poll_seconds: int = 20,   # kept for backward-compat; used for unexpected errors
    headless: bool = False,
):
    deadline = time.time() + max_wait_minutes * 60

    if not STATE_PATH.exists():
        raise FileNotFoundError("state.json not found! Please run login_setup.py first.")

    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=headless,
            args=[
                "--use-fake-ui-for-media-stream",
                "--autoplay-policy=no-user-gesture-required",
            ],
        )
        context = await browser.new_context(storage_state=str(STATE_PATH))
        page = await context.new_page()

        # ── Initial navigation ────────────────────────────────────────────────
        print(f"[{spec.name}] Going to dashboard...")
        await page.goto(DASHBOARD_URL)

        if "login" in page.url:
            await browser.close()
            raise RuntimeError("Session expired! Please re-run login_setup.py.")

        print(f"[{spec.name}] Waiting 10 seconds before entering the course page...")
        await asyncio.sleep(10)

        ok = await _navigate_to_course(page, spec)
        if not ok:
            await browser.close()
            raise RuntimeError(f"[{spec.name}] Could not reach course page on first try.")

        # ── Poll loop ─────────────────────────────────────────────────────────
        attempt = 0
        last_error = None

        while time.time() < deadline:
            attempt += 1
            print(f"[{spec.name}] Checking for join button (attempt {attempt})...")

            status, join_button = await _try_find_join_button(page, spec)

            # ── Case 1: button is ready ───────────────────────────────────────
            if status == "found":
                print(f"[{spec.name}] ✅ Join button found! Clicking...")
                try:
                    pages_before = len(context.pages)
                    await join_button.click()
                    await asyncio.sleep(3)

                    if len(context.pages) > pages_before:
                        current_page = context.pages[-1]
                        print(f"✅ [{spec.name}] BBB is loading in a NEW tab...")
                    else:
                        current_page = page
                        print(f"✅ [{spec.name}] BBB is loading in the SAME tab...")

                    # ── Audio modal ───────────────────────────────────────────
                    joined_audio = False
                    try:
                        print(
                            f"[{spec.name}] Waiting for BigBlueButton React App "
                            f"to load (up to 60 s)..."
                        )
                        await current_page.wait_for_selector(
                            ".ReactModal__Overlay", state="visible", timeout=60_000
                        )
                        print(f"[{spec.name}] Audio modal detected!")

                        listen_button = current_page.locator(
                            "button:has(.icon-bbb-listen)"
                        ).first
                        await listen_button.wait_for(state="visible", timeout=10_000)
                        await listen_button.click()

                        print(f"✅✅ [{spec.name}] Successfully joined audio as listen-only!")
                        joined_audio = True

                    except Exception as e:
                        print(f"⚠️ [{spec.name}] Could not click audio button. Error: {e}")
                        await current_page.screenshot(path=f"error_{spec.name}.png")

                    # ── Stay alive until class ends ───────────────────────────
                    # We stay alive regardless of whether audio join succeeded,
                    # so the user can interact manually if needed.
                    await _keep_alive_until_class_ends(current_page, spec, browser)
                    return  # ← clean exit after class finishes

                except Exception as e:
                    last_error = e
                    print(f"[{spec.name}] ⚠️ Error after clicking button: {e}")
                    # Fall through to refresh logic below

            # ── Case 2: class hasn't started yet (or button not clickable) ────
            elif status == "not_started":
                remaining = int(deadline - time.time())
                print(
                    f"[{spec.name}] 🕐 'هنوز برگزار نشده' detected "
                    f"(or button not clickable). "
                    f"Refreshing course page in {NOT_STARTED_REFRESH_INTERVAL}s "
                    f"(~{remaining}s left before deadline)..."
                )
                await asyncio.sleep(NOT_STARTED_REFRESH_INTERVAL)

                try:
                    await page.reload(wait_until="domcontentloaded", timeout=20_000)
                    await asyncio.sleep(2)
                except Exception as reload_err:
                    print(
                        f"[{spec.name}] Reload failed ({reload_err}), "
                        f"re-navigating via dashboard..."
                    )
                    await _navigate_to_course(page, spec)
                continue

            # ── Case 3: row / button completely missing ───────────────────────
            else:  # "missing"
                remaining = int(deadline - time.time())
                print(
                    f"[{spec.name}] ❓ Join button not found on page. "
                    f"Refreshing course page in {NOT_STARTED_REFRESH_INTERVAL}s "
                    f"(~{remaining}s left before deadline)..."
                )
                await asyncio.sleep(NOT_STARTED_REFRESH_INTERVAL)

                try:
                    await page.reload(wait_until="domcontentloaded", timeout=20_000)
                    await asyncio.sleep(2)
                except Exception as reload_err:
                    print(
                        f"[{spec.name}] Reload failed ({reload_err}), "
                        f"re-navigating via dashboard..."
                    )
                    await _navigate_to_course(page, spec)
                continue

        # ── Deadline exceeded ─────────────────────────────────────────────────
        await browser.close()
        raise RuntimeError(
            f"Failed to join {spec.name} after {max_wait_minutes} minutes"
        ) from last_error


if __name__ == "__main__":
    asyncio.run(join_class(ClassSpec(name="Test Course", start_time="15:00")))