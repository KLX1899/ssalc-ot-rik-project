# login_setup_nima.py
import asyncio
import os
from pathlib import Path
from playwright.async_api import async_playwright
import dotenv

dotenv.load_dotenv()

STATE_PATH = Path("state_nima.json")
LOGIN_URL  = "https://lms.aut.ac.ir/"
AFTER_URL  = "**/users-panel/**"


def get_credentials():
    username = os.environ.get("LMS_USERNAME")
    password = os.environ.get("LMS_PASSWORD")
    if not username or not password:
        raise RuntimeError(
            "LMS_USERNAME and LMS_PASSWORD must be set in the .env file!"
        )
    return username, password


async def do_login_nima(headless: bool = False) -> None:
    """
    Performs a full CAS SSO login for the NIMA (lms.aut.ac.ir) platform
    and saves the session to state_nima.json.
    Can be called from other modules — not just from __main__.
    """
    username, password = get_credentials()

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=headless)
        context = await browser.new_context()
        page    = await context.new_page()

        print("[NIMA Login] Navigating to https://lms.aut.ac.ir/ ...")
        await page.goto(LOGIN_URL)

        print("[NIMA Login] Waiting for redirect to login page...")
        await page.wait_for_url("**/login/index.php**", timeout=15_000)

        print("[NIMA Login] Clicking SSO button...")
        await page.get_by_text("ورود با سامانه یکپارچه", exact=False).click()

        print("[NIMA Login] Filling credentials...")
        await page.locator("#username").fill(username)
        await page.locator("#password").fill(password)
        await page.locator("#password").press("Enter")

        print("[NIMA Login] Waiting for NIMA panel...")
        await page.wait_for_url(AFTER_URL, timeout=30_000)

        await context.storage_state(path=str(STATE_PATH))
        print(f"[NIMA Login] ✅ Session saved to {STATE_PATH.resolve()}")

        await browser.close()


async def main():
    await do_login_nima(headless=False)


if __name__ == "__main__":
    asyncio.run(main())