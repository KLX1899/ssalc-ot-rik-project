# joiner.py
import asyncio
import time
from dataclasses import dataclass
from pathlib import Path
from playwright.async_api import async_playwright, TimeoutError as PWTimeoutError

STATE_PATH_LMS  = Path("state.json")
STATE_PATH_NIMA = Path("state_nima.json")

DASHBOARD_URL_LMS  = "https://lmshome.aut.ac.ir/panel/home"
DASHBOARD_URL_NIMA = "https://lms.aut.ac.ir/users-panel/announcements-list"

NOT_STARTED_REFRESH_INTERVAL = 60  # 1 minute


@dataclass(frozen=True)
class ClassSpec:
    name: str
    start_time: str       # e.g. "08:00"
    end_time: str = ""    # e.g. "10:00"
    platform: str = "LMS" # "LMS" or "NIMA"


# =============================================================================
#  LMS HELPERS
# =============================================================================

async def _lms_try_find_join_button(page, spec: ClassSpec):
    """
    Scans the LMS course page for a BBB join button in the row that matches
    spec.start_time.

    Returns one of:
      ("found",       button_locator)
      ("not_started", None)
      ("missing",     None)
    """
    try:
        row = page.locator("tr").filter(has_text=spec.start_time).first
        if await row.count() == 0:
            return ("missing", None)

        not_started = row.locator("text=هنوز برگزار نشده")
        if await not_started.count() > 0:
            return ("not_started", None)

        btn = row.locator("button", has_text="ورود به جلسه بیگبلوباتن").first
        if await btn.count() == 0:
            return ("missing", None)

        if await btn.is_visible() and await btn.is_enabled():
            return ("found", btn)
        return ("not_started", None)

    except Exception as e:
        print(f"[{spec.name}] _lms_try_find_join_button error: {e}")
        return ("missing", None)


async def _lms_navigate_to_course(page, spec: ClassSpec) -> bool:
    try:
        await page.goto(DASHBOARD_URL_LMS)
        await page.locator("a.course-link").first.wait_for(state="visible", timeout=15_000)

        link = page.locator("a.course-link").filter(has_text=spec.name).first
        await link.wait_for(state="visible", timeout=10_000)
        await link.click()
        print(f"[{spec.name}] Navigated to LMS course page.")
        await asyncio.sleep(3)
        return True
    except Exception as e:
        print(f"[{spec.name}] _lms_navigate_to_course error: {e}")
        return False


# =============================================================================
#  NIMA HELPERS
# =============================================================================

async def _nima_navigate_to_dashboard(page, spec: ClassSpec) -> bool:
    """Navigate (or re-navigate) to the NIMA ongoing-meetings panel."""
    try:
        await page.goto(DASHBOARD_URL_NIMA, wait_until="domcontentloaded")
        # Wait for the Angular app to finish rendering the ongoing-meetings table
        await page.wait_for_selector(
            "app-users-panel-ongoing-meetings",
            state="attached",
            timeout=20_000,
        )
        # Give Angular a moment to populate rows
        await asyncio.sleep(3)
        print(f"[{spec.name}] Navigated to NIMA dashboard.")
        return True
    except Exception as e:
        print(f"[{spec.name}] _nima_navigate_to_dashboard error: {e}")
        return False


async def _nima_try_find_join_button(page, spec: ClassSpec):
    """
    Reads the real HTML structure of lms.aut.ac.ir.

    The ongoing-meetings section is:
      app-users-panel-ongoing-meetings
        p-table
          tbody.p-datatable-tbody
            tr   ← one per active session
              td  → <button name="join">ورود</button>
              td  → <div class="p-text-nowrap p-text-truncate"> CLASS NAME </div>
              td  → time range e.g. " 08:00 - 10:00 "
              td  → date
              ...

    Matching strategy (most reliable → least reliable):
      1. Primary  : match row by time range  "HH:MM - HH:MM"
      2. Secondary: also verify the class name fragment is somewhere in the row
                    (guards against two classes at the same hour)

    Returns one of:
      ("found",       button_locator)
      ("not_started", None)   — table exists but our row is absent / button disabled
      ("missing",     None)   — the whole ongoing-meetings table is not rendered yet
    """
    try:
        # 1. Wait for Angular to render the component (non-blocking — already
        #    waited in navigate, but re-check cheaply here)
        ongoing = page.locator("app-users-panel-ongoing-meetings")
        if await ongoing.count() == 0:
            print(f"[{spec.name}] NIMA: app-users-panel-ongoing-meetings not found.")
            return ("missing", None)

        # 2. Build the time-range string the way NIMA renders it
        #    The <td> contains " 08:00 - 10:00 " (spaces around the dash)
        time_range = f"{spec.start_time} - {spec.end_time}"

        # 3. Get all <tr> rows inside the datatable body
        rows = ongoing.locator("tbody.p-datatable-tbody tr")
        row_count = await rows.count()

        if row_count == 0:
            print(f"[{spec.name}] NIMA: no active sessions in the table.")
            return ("not_started", None)

        print(f"[{spec.name}] NIMA: {row_count} active session(s) found. "
              f"Looking for time range '{time_range}'...")

        matched_row = None
        for i in range(row_count):
            row = rows.nth(i)
            row_text = await row.inner_text()

            # Normalise whitespace for comparison
            row_text_normalised = " ".join(row_text.split())

            # Primary match: time range must be present
            if time_range not in row_text_normalised:
                continue

            # Secondary match: at least a few characters of the course name
            # Use the first 6 meaningful characters of spec.name as a fragment
            # (avoids Arabic/Persian normalisation issues completely)
            name_fragment = spec.name[:6].strip()
            if name_fragment and name_fragment not in row_text_normalised:
                print(f"[{spec.name}] NIMA: row time matched but name fragment "
                      f"'{name_fragment}' not in row text — skipping.")
                continue

            matched_row = row
            print(f"[{spec.name}] NIMA: matched row → {row_text_normalised[:80]}...")
            break

        if matched_row is None:
            print(f"[{spec.name}] NIMA: no row matched time='{time_range}'.")
            return ("not_started", None)

        # 4. Find the join button inside the matched row
        btn = matched_row.locator('button[name="join"]').first
        if await btn.count() == 0:
            print(f"[{spec.name}] NIMA: row found but no join button inside it.")
            return ("not_started", None)

        is_visible = await btn.is_visible()
        is_enabled = await btn.is_enabled()

        if is_visible and is_enabled:
            return ("found", btn)

        print(f"[{spec.name}] NIMA: join button present but "
              f"visible={is_visible} enabled={is_enabled}.")
        return ("not_started", None)

    except Exception as e:
        print(f"[{spec.name}] _nima_try_find_join_button error: {e}")
        return ("missing", None)


# =============================================================================
#  SHARED HELPERS
# =============================================================================

async def _handle_bbb_audio(current_page, spec: ClassSpec):
    """Wait for the BBB audio modal and click 'listen only'."""
    try:
        print(f"[{spec.name}] Waiting for BBB audio modal (up to 60 s)...")
        await current_page.wait_for_selector(
            ".ReactModal__Overlay", state="visible", timeout=60_000
        )
        print(f"[{spec.name}] Audio modal detected!")

        listen_btn = current_page.locator("button:has(.icon-bbb-listen)").first
        await listen_btn.wait_for(state="visible", timeout=10_000)
        await listen_btn.click()
        print(f"✅✅ [{spec.name}] Joined audio as listen-only!")

    except Exception as e:
        print(f"⚠️ [{spec.name}] Could not click audio button: {e}")
        await current_page.screenshot(path=f"error_{spec.name}.png")


async def _keep_alive_until_class_ends(current_page, spec: ClassSpec):
    """Keep the browser open until end_time + 5 min, or until page closes."""
    import datetime
    import pytz

    tehran = pytz.timezone("Asia/Tehran")

    if spec.end_time:
        now = datetime.datetime.now(tehran)
        h, m = map(int, spec.end_time.split(":"))
        end_dt = now.replace(hour=h, minute=m, second=0, microsecond=0)
        if end_dt < now:
            end_dt += datetime.timedelta(days=1)
        end_dt += datetime.timedelta(minutes=5)

        wait = (end_dt - datetime.datetime.now(tehran)).total_seconds()
        print(
            f"[{spec.name}] 🎓 Staying in class until "
            f"{end_dt.strftime('%H:%M')} ({int(wait // 60)} min)..."
        )
        await asyncio.sleep(max(wait, 0))
        print(f"[{spec.name}] ⏰ Class time is over. Closing browser.")
    else:
        print(f"[{spec.name}] 🎓 No end_time — keeping browser until page closes...")
        try:
            await current_page.wait_for_event("close", timeout=0)
        except Exception:
            pass


# =============================================================================
#  MAIN JOIN FUNCTION
# =============================================================================

async def join_class(
    spec: ClassSpec,
    *,
    max_wait_minutes: int = 15,
    poll_seconds: int = 20,
    headless: bool = False,
):
    is_nima    = spec.platform.upper() == "NIMA"
    state_path = STATE_PATH_NIMA if is_nima else STATE_PATH_LMS
    deadline   = time.time() + max_wait_minutes * 60

    if not state_path.exists():
        script = "login_setup_nima.py" if is_nima else "login_setup.py"
        raise FileNotFoundError(
            f"{state_path} not found! Please run {script} first."
        )

    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=headless,
            args=[
                "--use-fake-ui-for-media-stream",
                "--autoplay-policy=no-user-gesture-required",
            ],
        )
        context = await browser.new_context(storage_state=str(state_path))
        page    = await context.new_page()

        # ── Initial navigation ────────────────────────────────────────────
        dashboard = DASHBOARD_URL_NIMA if is_nima else DASHBOARD_URL_LMS
        print(f"[{spec.name}] Platform: {spec.platform}  →  {dashboard}")
        await page.goto(dashboard)

        if "login" in page.url:
            await browser.close()
            script = "login_setup_nima.py" if is_nima else "login_setup.py"
            raise RuntimeError(f"Session expired! Please re-run {script}.")

        print(f"[{spec.name}] Waiting 10 s before first check...")
        await asyncio.sleep(10)

        # Navigate to the correct starting point
        if is_nima:
            ok = await _nima_navigate_to_dashboard(page, spec)
        else:
            ok = await _lms_navigate_to_course(page, spec)

        if not ok:
            await browser.close()
            raise RuntimeError(f"[{spec.name}] Could not reach course page.")

        # ── Poll loop ─────────────────────────────────────────────────────
        attempt    = 0
        last_error = None

        while time.time() < deadline:
            attempt += 1
            print(f"[{spec.name}] Checking for join button (attempt {attempt})...")

            if is_nima:
                status, join_button = await _nima_try_find_join_button(page, spec)
            else:
                status, join_button = await _lms_try_find_join_button(page, spec)

            # ── FOUND ─────────────────────────────────────────────────────
            if status == "found":
                print(f"[{spec.name}] ✅ Join button found! Clicking...")
                try:
                    pages_before = len(context.pages)
                    await join_button.click()
                    await asyncio.sleep(5)

                    if len(context.pages) > pages_before:
                        current_page = context.pages[-1]
                        print(f"✅ [{spec.name}] BBB loading in a NEW tab...")
                    else:
                        current_page = page
                        print(f"✅ [{spec.name}] BBB loading in the SAME tab...")

                    await _handle_bbb_audio(current_page, spec)
                    await _keep_alive_until_class_ends(current_page, spec)
                    return  # ← clean exit

                except Exception as e:
                    last_error = e
                    print(f"[{spec.name}] ⚠️ Error after clicking: {e}")
                    # fall through to refresh

            # ── NOT STARTED ───────────────────────────────────────────────
            elif status == "not_started":
                remaining = int(deadline - time.time())
                print(
                    f"[{spec.name}] 🕐 Class not started yet. "
                    f"Refreshing in {NOT_STARTED_REFRESH_INTERVAL}s "
                    f"(~{remaining}s left)..."
                )
                await asyncio.sleep(NOT_STARTED_REFRESH_INTERVAL)
                try:
                    await page.reload(wait_until="domcontentloaded", timeout=20_000)
                    await asyncio.sleep(3)
                except Exception:
                    if is_nima:
                        await _nima_navigate_to_dashboard(page, spec)
                    else:
                        await _lms_navigate_to_course(page, spec)
                continue

            # ── MISSING ───────────────────────────────────────────────────
            else:
                remaining = int(deadline - time.time())
                print(
                    f"[{spec.name}] ❓ Component/button not found. "
                    f"Re-navigating in {NOT_STARTED_REFRESH_INTERVAL}s "
                    f"(~{remaining}s left)..."
                )
                await asyncio.sleep(NOT_STARTED_REFRESH_INTERVAL)
                if is_nima:
                    await _nima_navigate_to_dashboard(page, spec)
                else:
                    await _lms_navigate_to_course(page, spec)
                continue

        # ── Deadline exceeded ─────────────────────────────────────────────
        await browser.close()
        raise RuntimeError(
            f"Failed to join {spec.name} after {max_wait_minutes} min"
        ) from last_error


if __name__ == "__main__":
    asyncio.run(
        join_class(ClassSpec(
            name="آزمايشگاه شبکه‌هاي کامپيوتري",
            start_time="08:00",
            end_time="10:00",
            platform="NIMA",
        ))
    )