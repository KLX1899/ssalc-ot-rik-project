# login_setup.py
import asyncio
import os
from pathlib import Path
from playwright.async_api import async_playwright
import dotenv

# Load environment variables from .env file
dotenv.load_dotenv()

STATE_PATH = Path("state.json")

# Get credentials from environment variables
USERNAME = os.environ.get("LMS_USERNAME")
PASSWORD = os.environ.get("LMS_PASSWORD")

if not USERNAME or not PASSWORD:
    print("LMS_USERNAME and LMS_PASSWORD must be set in the .env file!")
    exit(1)


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=False)
        context = await browser.new_context()
        page = await context.new_page()

        # 1. Start exactly at the LMS home as requested
        print("Navigating to https://lmshome.aut.ac.ir/ ...")
        await page.goto("https://lmshome.aut.ac.ir/")

        # 2. Wait for the automatic redirect to the courses login page
        print("Waiting for automatic redirect to the login page...")
        await page.wait_for_url("**/login/index.php**", timeout=15_000)

        # 3. Now hit the SSO button
        print("Clicking 'ورود با سامانه یکپارچه' (SSO)...")
        await page.get_by_text("ورود با سامانه یکپارچه", exact=False).click()

        # 4. Wait for the CAS system and fill credentials
        print("Waiting for CAS login page...")
        await page.locator("#username").fill(USERNAME)
        await page.locator("#password").fill(PASSWORD)

        print("Submitting credentials...")
        await page.locator("#password").press("Enter")

        # 5. Wait to be redirected back to the dashboard
        print("Waiting to be redirected to the dashboard...")
        await page.wait_for_url("**/panel/home**", timeout=30_000)

        # Save cookies & auth state
        await context.storage_state(path=str(STATE_PATH))
        print(f"✅ Logged in successfully! Session saved to {STATE_PATH.resolve()}")

        await browser.close()


if __name__ == "__main__":
    asyncio.run(main())