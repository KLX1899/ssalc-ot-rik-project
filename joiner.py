# joiner.py
import asyncio
import time
from dataclasses import dataclass
from pathlib import Path
from playwright.async_api import async_playwright, TimeoutError as PWTimeoutError

STATE_PATH = Path("state.json")
DASHBOARD_URL = "https://lmshome.aut.ac.ir/panel/home"

@dataclass(frozen=True)
class ClassSpec:
    name: str  
    start_time: str  # e.g., "15:00"

async def join_class(
    spec: ClassSpec,
    *,
    max_wait_minutes: int = 15,
    poll_seconds: int = 20,
    headless: bool = False,
):
    deadline = time.time() + max_wait_minutes * 60

    if not STATE_PATH.exists():
        raise FileNotFoundError("state.json not found! Please run login_setup.py first.")

    async with async_playwright() as p:
        # Pass flags to automatically allow audio playback without user interaction
        browser = await p.chromium.launch(
            headless=headless,
            args=[
                "--use-fake-ui-for-media-stream",
                "--autoplay-policy=no-user-gesture-required"
            ]
        )
        context = await browser.new_context(storage_state=str(STATE_PATH))  
        page = await context.new_page()
        
        print(f"[{spec.name}] Going to dashboard...")
        await page.goto(DASHBOARD_URL)

        if "login" in page.url:
            await browser.close()
            raise RuntimeError("Session expired! Please re-run login_setup.py.")

        # 1. Wait for dashboard to load
        await page.locator("a.course-link").first.wait_for(state="visible", timeout=15_000)

        # 2. Click the course
        course_link = page.locator("a.course-link").filter(has_text=spec.name).first
        await course_link.wait_for(state="visible", timeout=10_000)
        
        print(f"[{spec.name}] Waiting 10 seconds before entering the course page...")
        await asyncio.sleep(10)

        await course_link.click()
        print(f"[{spec.name}] Navigated to course page.")
        
        # 3. Find the BBB join button for the correct time
        join_button = page.locator("tr") \
                          .filter(has_text=spec.start_time) \
                          .locator("button", has_text="ورود به جلسه بیگبلوباتن") \
                          .first 

        attempt = 0
        last_error = None

        while time.time() < deadline:
            attempt += 1
            try:
                print(f"[{spec.name}] Waiting for BBB button for {spec.start_time} (Attempt {attempt})...")
                await join_button.wait_for(state="visible", timeout=10_000)
                
                # --- FIXED NAVIGATION LOGIC ---
                # We click ONCE. We check if a new tab opened. If not, we use the same tab.
                pages_before = len(context.pages)
                await join_button.click()
                
                # Wait a moment for browser to decide if it's opening a new tab
                await asyncio.sleep(3) 
                
                if len(context.pages) > pages_before:
                    current_page = context.pages[-1]
                    print(f"✅ [{spec.name}] BBB is loading in a NEW tab...")
                else:
                    current_page = page
                    print(f"✅ [{spec.name}] BBB is loading in the SAME tab...")
                # ------------------------------

                # --- FIXED AUDIO MODAL LOGIC ---
                try:
                    print(f"[{spec.name}] Waiting for BigBlueButton React App to load (this can take up to 60s)...")
                    
                    # 1. Wait for the dark overlay behind the modal to exist
                    await current_page.wait_for_selector(".ReactModal__Overlay", state="visible", timeout=60_000)
                    print(f"[{spec.name}] Audio modal detected!")
                    
                    # 2. Find the button using the EXACT headphone icon class (ignores language issues)
                    listen_button = current_page.locator("button:has(.icon-bbb-listen)").first
                    
                    # 3. Wait for it to be clickable, then click
                    await listen_button.wait_for(state="visible", timeout=10_000)
                    await listen_button.click()
                    
                    print(f"✅✅ [{spec.name}] Successfully joined audio as listen-only!")
                    
                except Exception as e:
                    print(f"⚠️ [{spec.name}] Could not click audio button. Error: {e}")
                    # Takes a screenshot so you can see exactly where it got stuck
                    await current_page.screenshot(path=f"error_{spec.name}.png")
                # -------------------------------
                
                # Keep browser open for 10 seconds before terminating the script function
                await asyncio.sleep(10)
                return

            except PWTimeoutError as e:
                last_error = e
                print(f"[{spec.name}] Button not found yet. Refreshing...")
                await page.reload()
                await asyncio.sleep(poll_seconds)
            except Exception as e:
                last_error = e
                print(f"[{spec.name}] Unexpected error: {e}")
                await asyncio.sleep(poll_seconds)

        await browser.close()
        raise RuntimeError(f"Failed to join {spec.name} after {max_wait_minutes} minutes") from last_error

if __name__ == "__main__":
    # Example usage
    asyncio.run(join_class(ClassSpec(name="Test Course", start_time="15:00")))