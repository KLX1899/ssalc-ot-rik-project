# joiner.py
import asyncio
import time
from dataclasses import dataclass
from pathlib import Path
from playwright.async_api import async_playwright, TimeoutError as PWTimeoutError

# Imported lazily inside functions to avoid circular imports at module level
STATE_PATH_LMS  = Path("state.json")
STATE_PATH_NIMA = Path("state_nima.json")

DASHBOARD_URL_LMS  = "https://lmshome.aut.ac.ir/panel/home"
DASHBOARD_URL_NIMA = "https://lms.aut.ac.ir/users-panel/announcements-list"

NOT_STARTED_REFRESH_INTERVAL = 60  # seconds


@dataclass(frozen=True)
class ClassSpec:
    name: str
    start_time: str        # e.g. "08:00"
    end_time: str  = ""    # e.g. "10:00"
    platform: str  = "LMS" # "LMS" or "NIMA"


# =============================================================================
#  LOGIN HELPERS  (thin wrappers — real logic lives in login_setup*.py)
# =============================================================================

async def _ensure_logged_in(spec: ClassSpec, headless: bool = False) -> None:
    """
    Checks whether a valid state file exists. If not, runs the appropriate
    login flow automatically.
    """
    is_nima    = spec.platform.upper() == "NIMA"
    state_path = STATE_PATH_NIMA if is_nima else STATE_PATH_LMS

    if not state_path.exists():
        print(f"[{spec.name}] ⚠️  {state_path} not found — logging in automatically...")
        await _do_login(is_nima, headless)


async def _do_login(is_nima: bool, headless: bool = False) -> None:
    """Calls the correct login coroutine from the login_setup modules."""
    if is_nima:
        from login_setup_nima import do_login_nima
        await do_login_nima(headless=headless)
    else:
        from login_setup import do_login_lms
        await do_login_lms(headless=headless)


# =============================================================================
#  LMS HELPERS
# =============================================================================

async def _lms_try_find_join_button(page, spec: ClassSpec):
    """
    Returns:
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
        await page.locator("a.course-link").first.wait_for(
            state="visible", timeout=15_000
        )
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
    try:
        await page.goto(DASHBOARD_URL_NIMA, wait_until="domcontentloaded")
        await page.wait_for_selector(
            "app-users-panel-ongoing-meetings",
            state="attached",
            timeout=20_000,
        )
        await asyncio.sleep(3)
        print(f"[{spec.name}] Navigated to NIMA dashboard.")
        return True
    except Exception as e:
        print(f"[{spec.name}] _nima_navigate_to_dashboard error: {e}")
        return False


async def _nima_try_find_join_button(page, spec: ClassSpec):
    """
    Matches the active-session table row by time range, then finds the
    ورود (join) button inside it.

    Returns:
      ("found",       button_locator)
      ("not_started", None)
      ("missing",     None)
    """
    try:
        ongoing = page.locator("app-users-panel-ongoing-meetings")
        if await ongoing.count() == 0:
            print(f"[{spec.name}] NIMA: ongoing-meetings component not found.")
            return ("missing", None)

        time_range = f"{spec.start_time} - {spec.end_time}"
        rows       = ongoing.locator("tbody.p-datatable-tbody tr")
        row_count  = await rows.count()

        if row_count == 0:
            print(f"[{spec.name}] NIMA: no active sessions.")
            return ("not_started", None)

        print(
            f"[{spec.name}] NIMA: {row_count} active session(s). "
            f"Looking for '{time_range}'..."
        )

        matched_row = None
        for i in range(row_count):
            row      = rows.nth(i)
            row_text = " ".join((await row.inner_text()).split())

            if time_range not in row_text:
                continue

            # Secondary guard: first 6 chars of spec.name
            name_fragment = spec.name[:6].strip()
            if name_fragment and name_fragment not in row_text:
                print(
                    f"[{spec.name}] NIMA: time matched but name fragment "
                    f"'{name_fragment}' absent — skipping."
                )
                continue

            matched_row = row
            print(f"[{spec.name}] NIMA: matched → {row_text[:80]}...")
            break

        if matched_row is None:
            print(f"[{spec.name}] NIMA: no row matched '{time_range}'.")
            return ("not_started", None)

        btn = matched_row.locator('button[name="join"]').first
        if await btn.count() == 0:
            return ("not_started", None)

        if await btn.is_visible() and await btn.is_enabled():
            return ("found", btn)

        return ("not_started", None)

    except Exception as e:
        print(f"[{spec.name}] _nima_try_find_join_button error: {e}")
        return ("missing", None)


# =============================================================================
#  SHARED HELPERS
# =============================================================================

def _is_login_page(url: str) -> bool:
    """Returns True if the browser has been redirected to the CAS login page."""
    return "login" in url or "cas" in url


async def _handle_bbb_audio(current_page, spec: ClassSpec) -> None:
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


async def _keep_alive_until_class_ends(current_page, spec: ClassSpec) -> None:
    import datetime, pytz

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
            f"[{spec.name}] 🎓 Staying until "
            f"{end_dt.strftime('%H:%M')} ({int(wait // 60)} min)..."
        )
        await asyncio.sleep(max(wait, 0))
        print(f"[{spec.name}] ⏰ Class over. Closing browser.")
    else:
        print(f"[{spec.name}] No end_time — waiting for page to close...")
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
    poll_seconds: int = 20,   # kept for API compatibility
    headless: bool = False,
) -> None:
    is_nima    = spec.platform.upper() == "NIMA"
    state_path = STATE_PATH_NIMA if is_nima else STATE_PATH_LMS
    deadline   = time.time() + max_wait_minutes * 60

    # ── Step 0: Ensure we are logged in ──────────────────────────────────────
    await _ensure_logged_in(spec, headless=headless)

    # ── Step 1: Open browser ──────────────────────────────────────────────────
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

        dashboard = DASHBOARD_URL_NIMA if is_nima else DASHBOARD_URL_LMS
        print(f"[{spec.name}] Platform: {spec.platform}  →  {dashboard}")

        # ── Step 2: Go to dashboard; re-login if session has expired ──────────
        await page.goto(dashboard)

        if _is_login_page(page.url):
            print(f"[{spec.name}] 🔑 Session expired — re-logging in automatically...")
            await browser.close()
            await _do_login(is_nima, headless=headless)

            # Reload context with the fresh state file
            browser = await p.chromium.launch(
                headless=headless,
                args=[
                    "--use-fake-ui-for-media-stream",
                    "--autoplay-policy=no-user-gesture-required",
                ],
            )
            context = await browser.new_context(storage_state=str(state_path))
            page    = await context.new_page()
            await page.goto(dashboard)

            # If still on login page after a fresh login, bail out
            if _is_login_page(page.url):
                await browser.close()
                raise RuntimeError(
                    f"[{spec.name}] Could not log in automatically. "
                    f"Please check credentials in .env"
                )

        print(f"[{spec.name}] Waiting 10 s before first check...")
        await asyncio.sleep(10)

        # ── Step 3: Navigate to the course / NIMA dashboard ───────────────────
        if is_nima:
            ok = await _nima_navigate_to_dashboard(page, spec)
        else:
            ok = await _lms_navigate_to_course(page, spec)

        if not ok:
            await browser.close()
            raise RuntimeError(f"[{spec.name}] Could not reach course page.")

        # ── Step 4: Poll loop ─────────────────────────────────────────────────
        attempt    = 0
        last_error = None

        while time.time() < deadline:
            attempt += 1
            print(f"[{spec.name}] Checking for join button (attempt {attempt})...")

            # Re-check for accidental session expiry mid-poll
            if _is_login_page(page.url):
                print(
                    f"[{spec.name}] 🔑 Session expired mid-session — "
                    f"re-logging in..."
                )
                await browser.close()
                await _do_login(is_nima, headless=headless)

                browser = await p.chromium.launch(
                    headless=headless,
                    args=[
                        "--use-fake-ui-for-media-stream",
                        "--autoplay-policy=no-user-gesture-required",
                    ],
                )
                context = await browser.new_context(storage_state=str(state_path))
                page    = await context.new_page()

                if is_nima:
                    await _nima_navigate_to_dashboard(page, spec)
                else:
                    await _lms_navigate_to_course(page, spec)
                continue

            if is_nima:
                status, join_button = await _nima_try_find_join_button(page, spec)
            else:
                status, join_button = await _lms_try_find_join_button(page, spec)

            # ── FOUND ─────────────────────────────────────────────────────────
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

            # ── NOT STARTED ───────────────────────────────────────────────────
            elif status == "not_started":
                remaining = int(deadline - time.time())
                print(
                    f"[{spec.name}] 🕐 Not started yet. "
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

            # ── MISSING ───────────────────────────────────────────────────────
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

        # ── Deadline exceeded ─────────────────────────────────────────────────
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