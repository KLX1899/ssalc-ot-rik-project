# AUT LMS Auto Joiner

A Python automation tool built with Playwright and APScheduler that automatically logs into Amirkabir University of Technology's online class platforms, waits for your scheduled classes to start, joins the sessions as "listen-only", and keeps the browser open until the class is over.

## Supported Platforms

| Platform | URL | Session File | Login Script |
|----------|-----|-------------|--------------|
| **LMS** (BigBlueButton) | `lmshome.aut.ac.ir` | `state.json` | `login_setup.py` |
| **NIMA** (نیما) | `lms.aut.ac.ir` | `state_nima.json` | `login_setup_nima.py` |

> **How to tell them apart:** If your class link lives on **lmshome.aut.ac.ir** (the Moodle-based BBB portal), it is **LMS**. If the professor set it up through the Angular-based **NIMA** panel at `lms.aut.ac.ir`, it is **NIMA**. The two NIMA classes for CS students are typically **انقلاب اسلامي ايران** and **آزمايشگاه شبکه‌هاي کامپيوتري**; everything else is LMS.

## Features

* **Dual-Platform Support**: Handles both LMS (BigBlueButton) and NIMA (نیما) class platforms with a single scheduler.
* **Automated Login**: Uses CAS SSO to log into both platforms and saves each session securely in a separate state file.
* **Smart Polling**: If the class has not started yet ("هنوز برگزار نشده"), the tool waits and refreshes the page every **1 minute** automatically.
* **Auto-Join**: Automatically clicks the required buttons to join the BigBlueButton audio in listen-only mode (LMS) or enters the NIMA classroom.
* **Persistent Browser**: After joining, the browser **stays open** for the full duration of the class (end time + 5-minute buffer) so you can interact with it manually if needed.
* **Catch-up Mechanism**: If you start the script while a class is already in progress, it will join immediately.
* **YAML Scheduling**: Easily manage your weekly class schedule using a simple configuration file with a `platform` field.
* **Timezone Aware**: Fully configured for Iran Standard Time (`Asia/Tehran`).

## Prerequisites

* Python 3.8 or higher
* Pip (Python package manager)

## Installation

1. Clone or download this repository.
2. Install the required Python packages:
   ```bash
   pip install -r requirements.txt
   ```
3. Install the Playwright Chromium browser:
   ```bash
   playwright install chromium
   ```

## Configuration

### 1. Credentials (`.env`)

Create a `.env` file in the root directory of the project with your AUT portal credentials:

```env
LMS_USERNAME=your_student_id
LMS_PASSWORD=your_password
```

> The same credentials are used for both LMS and NIMA since both use the university's CAS SSO system.

### 2. Schedule (`schedule.yaml`)

Create or edit the `schedule.yaml` file. Each class needs a `platform` field indicating which system hosts the class:

```yaml
classes:
  # ── NIMA classes ──────────────────────────────────
  - name: "انقلاب اسلامي ايران"
    days: ["sat"]
    start: "10:00"
    end: "12:00"
    platform: "NIMA"

  - name: "آزمايشگاه شبکه‌هاي کامپيوتري"
    days: ["sat"]
    start: "08:00"
    end: "10:00"
    platform: "NIMA"

  # ── LMS (BigBlueButton) classes ───────────────────
  - name: "شبکه‌هاي کامپيوتري"
    days: ["sat", "mon"]
    start: "15:00"
    end: "16:30"
    platform: "LMS"

  - name: "روش پژوهش و ارائه"
    days: ["mon"]
    start: "16:30"
    end: "18:00"
    platform: "LMS"
```

| Field | Required | Description |
|-------|----------|-------------|
| `name` | ✅ | Course name as it appears on the platform (LMS dashboard link text, or NIMA announcement title) |
| `days` | ✅ | List of weekdays: `sun`, `mon`, `tue`, `wed`, `thu`, `sat`, `fri` |
| `start` | ✅ | Class start time in 24-hour format (`HH:MM`) |
| `end` | ✅ | Class end time in 24-hour format (`HH:MM`). Used to keep the browser open until this time + 5 min buffer |
| `platform` | ✅ | `"LMS"` or `"NIMA"`. Defaults to `"LMS"` if omitted |

> **NIMA name matching note:** NIMA renders full names like `"انقلاب اسلامی ایران - گروه 01 - میرخلیلی"`. The tool matches by **time range** (e.g., `"10:00 - 12:00"`) as the primary key, then uses a prefix of the `name` field as a secondary check, so you don't need to type the full title — just the first few distinctive characters are enough.

## Usage

### Step 1: Initial Login

Run the login script **for each platform you use**. This opens a browser, performs CAS SSO authentication, and saves the session to a state file.

**For LMS classes** (most classes):
```bash
python login_setup.py
# → Creates state.json
```

**For NIMA classes** (انقلاب اسلامي ايران, آزمايشگاه شبکه‌هاي کامپيوتري):
```bash
python login_setup_nima.py
# → Creates state_nima.json
```

> You only need to run these once. Re-run if the session expires and you see a `Session expired` error.

### Step 2: Start the Scheduler

Once the state file(s) exist, run the scheduler. **Leave the terminal open** — it runs in the background and joins classes automatically at the scheduled times.

```bash
python scheduler.py
```

### How It Works

```
┌─────────────────────────────────────────────────────────┐
│                    scheduler.py                          │
│  CronTrigger fires at each class's start time            │
│           │                                              │
│           ▼                                              │
│  ┌─── join_class(spec) ──────────────────────────────┐  │
│  │                                                    │  │
│  │  1. Open browser with saved session                │  │
│  │  2. Navigate to course page                        │  │
│  │     ┌──────────────┬──────────────────┐           │  │
│  │     │ LMS          │ NIMA             │           │  │
│  │     │ lmshome →    │ lms.aut.ac.ir →  │           │  │
│  │     │ course page  │ ongoing meetings │           │  │
│  │     └──────────────┴──────────────────┘           │  │
│  │  3. Poll every 1 min if button not ready           │  │
│  │  4. Click Join → BBB opens → Audio listen-only     │  │
│  │  5. Stay open until end_time + 5 min buffer        │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Catch-up Mode

If you run `scheduler.py` while a class is **already in progress** (you started the script late), the scheduler detects this and launches `join_class()` immediately — no need to wait for the next cron trigger.

## File Structure

```
.
├── scheduler.py          # APScheduler-based cron scheduler
├── joiner.py             # Core logic: navigate, find button, join BBB
├── login_setup.py        # One-time LMS login (→ state.json)
├── login_setup_nima.py   # One-time NIMA login (→ state_nima.json)
├── schedule.yaml         # Your weekly class timetable
├── state.json            # (auto-generated) LMS session cookies
├── state_nima.json       # (auto-generated) NIMA session cookies
├── .env                  # Your credentials (do NOT commit this)
├── requirements.txt      # Python dependencies
└── README.md
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `state.json not found` | Run `python login_setup.py` first |
| `state_nima.json not found` | Run `python login_setup_nima.py` first |
| `Session expired` | Your session cookie has expired — re-run the appropriate `login_setup_*.py` |
| Button not found / stuck refreshing | The class may not be scheduled on the platform you configured. Double-check the `platform` field in `schedule.yaml` |
| NIMA: row matched but name not found | The `name` in your YAML may not match the first 6 characters of what NIMA displays. Use the course name exactly as shown on the NIMA panel |
| Audio modal not appearing | BBB may be loading slowly. The script waits up to 60 seconds. If it fails, a screenshot is saved as `error_<coursename>.png` |
| Browser closes unexpectedly | The browser now stays open until 5 minutes after the `end` time. If it closes, your system may have terminated the process. Check terminal output for errors |

---

# ورود خودکار به کلاس‌های آنلاین امیرکبیر

یک ابزار اتوماسیون پایتون با استفاده از Playwright و APScheduler که به صورت خودکار وارد سامانه‌های آنلاین دانشگاه صنعتی امیرکبیر می‌شود، منتظر زمان شروع کلاس می‌ماند، به عنوان «فقط شنونده» وارد کلاس می‌شود و مرورگر را تا پایان کلاس باز نگه می‌دارد.

## پلتفرم‌های پشتیبانی شده

| پلتفرم | آدرس | فایل نشست | اسکریپت ورود |
|---------|------|-----------|-------------|
| **LMS** (بیگ‌بلو‌باتن) | `lmshome.aut.ac.ir` | `state.json` | `login_setup.py` |
| **NIMA** (نیما) | `lms.aut.ac.ir` | `state_nima.json` | `login_setup_nima.py` |

> **تشخیص پلتفرم:** اگر لینک کلاس شما در **lmshome.aut.ac.ir** (پورتال BBB مبتنی بر Moodle) باشد، آن کلاس از نوع **LMS** است. اگر استاد آن را از طریق پنل Angular مبتنی بر **نیما** در `lms.aut.ac.ir` تنظیم کرده باشد، آن کلاس از نوع **NIMA** است. معمولاً دروس **انقلاب اسلامي ايران** و **آزمايشگاه شبکه‌هاي کامپيوتري** روی NIMA هستند و بقیه دروس روی LMS.

## ویژگی‌ها

* **پشتیبانی دو پلتفرمی**: مدیریت همزمان کلاس‌های LMS (بیگ‌بلو‌باتن) و NIMA (نیما) با یک زمان‌بند واحد.
* **ورود خودکار**: ورود از طریق سامانه یکپارچه (SSO) برای هر دو پلتفرم و ذخیره جداگانه نشست کاربری.
* **بررسی هوشمند وضعیت**: در صورتی که کلاس هنوز شروع نشده باشد ("هنوز برگزار نشده")، هر **۱ دقیقه** صفحه را رفرش می‌کند.
* **ورود خودکار**: کلیک خودکار دکمه‌های لازم برای ورود به BBB (حالت فقط شنونده) یا ورود به کلاس نیما.
* **مرورگر پایدار**: پس از ورود، مرورگر تا **پایان کلاس + ۵ دقیقه بافر** باز می‌ماند تا بتوانید در صورت نیاز تعامل داشته باشید.
* **قابلیت جبران (Catch-up)**: اگر اسکریپت را در حالی اجرا کنید که زمان کلاس از قبل شروع شده باشد، بلافاصله وارد آن کلاس می‌شود.
* **زمان‌بندی با YAML**: مدیریت آسان برنامه کلاسی هفتگی با فیلد `platform`.
* **پشتیبانی از منطقه زمانی**: تنظیم شده برای منطقه زمانی ایران (`Asia/Tehran`).

## پیش‌نیازها

* پایتون نسخه ۳.۸ یا بالاتر
* مدیریت بسته‌های پایتون (Pip)

## نصب

۱. این مخزن را دانلود یا Clone کنید.
۲. نیازمندی‌های پایتون را نصب کنید:
   ```bash
   pip install -r requirements.txt
   ```
۳. مرورگر کرومیوم مربوط به Playwright را نصب کنید:
   ```bash
   playwright install chromium
   ```

## تنظیمات

### ۱. اطلاعات ورود (`.env`)

یک فایل `.env` در مسیر اصلی پروژه ایجاد کنید و اطلاعات ورود پورتال دانشگاه را در آن قرار دهید:

```env
LMS_USERNAME=شماره_دانشجویی
LMS_PASSWORD=رمز_عبور
```

> از همان نام کاربری و رمز عبور برای هر دو پلتفرم استفاده می‌شود زیرا هر دو از سامانه CAS دانشگاه استفاده می‌کنند.

### ۲. برنامه کلاسی (`schedule.yaml`)

فایل `schedule.yaml` را ویرایش کنید. هر کلاس باید فیلد `platform` داشته باشد:

```yaml
classes:
  # ── کلاس‌های NIMA ──────────────────────────────────
  - name: "انقلاب اسلامي ايران"
    days: ["sat"]
    start: "10:00"
    end: "12:00"
    platform: "NIMA"

  - name: "آزمايشگاه شبکه‌هاي کامپيوتري"
    days: ["sat"]
    start: "08:00"
    end: "10:00"
    platform: "NIMA"

  # ── کلاس‌های LMS (بیگ‌بلو‌باتن) ───────────────────
  - name: "شبکه‌هاي کامپيوتري"
    days: ["sat", "mon"]
    start: "15:00"
    end: "16:30"
    platform: "LMS"

  - name: "روش پژوهش و ارائه"
    days: ["mon"]
    start: "16:30"
    end: "18:00"
    platform: "LMS"
```

| فیلد | ضروری | توضیحات |
|------|-------|---------|
| `name` | ✅ | نام درس دقیقاً مطابق با نمایش روی پلتفرم (متن لینک داشبورد LMS یا عنوان اعلان NIMA) |
| `days` | ✅ | لیست روزهای هفته: `sun` (یکشنبه)، `sat` (شنبه)، `mon` (دوشنبه)، ... |
| `start` | ✅ | ساعت شروع کلاس به فرمت ۲۴ ساعته (`HH:MM`) |
| `end` | ✅ | ساعت پایان کلاس به فرمت ۲۴ ساعته (`HH:MM`). مرورگر تا این ساعت + ۵ دقیقه باز می‌ماند |
| `platform` | ✅ | `"LMS"` یا `"NIMA"`. اگر خالی باشد به صورت پیش‌فرض `LMS` در نظر گرفته می‌شود |

> **نکته درباره تطبیق نام در NIMA:** نیما نام کاملی مثل `"انقلاب اسلامی ایران - گروه 01 - میرخلیلی"` را نمایش می‌دهد. ابزار ابتدا با **بازه زمانی** (مثلاً `"10:00 - 12:00"`) تطبیق می‌دهد و سپس چند حرف اول نام درس را به عنوان بررسی تکمیلی استفاده می‌کند. پس فقط کافی است اولین کلمات متمایز درس را وارد کنید — نیازی به نوشتن نام کامل نیست.

## نحوه استفاده

### مرحله اول: ورود اولیه

اسکریپت ورود را **برای هر پلتفرمی که استفاده می‌کنید** اجرا کنید. این کار مرورگر را باز کرده، از طریق CAS وارد می‌شود و نشست را ذخیره می‌کند.

**برای کلاس‌های LMS** (بیشتر دروس):
```bash
python login_setup.py
# → فایل state.json ایجاد می‌شود
```

**برای کلاس‌های NIMA** (انقلاب اسلامي ايران، آزمايشگاه شبکه‌هاي کامپيوتري):
```bash
python login_setup_nima.py
# → فایل state_nima.json ایجاد می‌شود
```

> فقط یک بار نیاز به اجرای این اسکریپت‌ها دارید. اگر با خطای `Session expired` مواجه شدید، دوباره اجرا کنید.

### مرحله دوم: اجرای زمان‌بند

پس از ایجاد فایل‌های state، زمان‌بند را اجرا کنید. **این ترمینال را باز بگذارید** تا ربات به صورت خودکار در زمان‌های مقرر وارد کلاس‌ها شود:

```bash
python scheduler.py
```

### نحوه کار

```
┌─────────────────────────────────────────────────────────┐
│                    scheduler.py                          │
│  CronTrigger در ساعت شروع هر کلاس فعال می‌شود          │
│           │                                              │
│           ▼                                              │
│  ┌─── join_class(spec) ──────────────────────────────┐  │
│  │                                                    │  │
│  │  ۱. باز کردن مرورگر با نشست ذخیره شده            │  │
│  │  ۲. رفتن به صفحه کلاس                             │  │
│  │     ┌──────────────┬──────────────────┐           │  │
│  │     │ LMS          │ NIMA             │           │  │
│  │     │ lmshome →    │ lms.aut.ac.ir →  │           │  │
│  │     │ صفحه درس     │ جلسات فعال       │           │  │
│  │     └──────────────┴──────────────────┘           │  │
│  │  ۳. بررسی هر ۱ دقیقه اگر دکمه آماده نیست        │  │
│  │  ۴. کلیک ورود → باز شدن BBB → فقط شنونده         │  │
│  │  ۵. باز ماندن تا end_time + ۵ دقیقه بافر          │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### قابلیت جبران (Catch-up)

اگر اسکریپت را زمانی اجرا کنید که کلاس **از قبل در حال برگزاری** است، زمان‌بند این وضعیت را تشخیص داده و بلافاصله `join_class()` را اجرا می‌کند — نیازی به انتظار برای تریگر بعدی نیست.

## ساختار فایل‌ها

```
.
├── scheduler.py          # زمان‌بند مبتنی بر APScheduler
├── joiner.py             # منطق اصلی: پیمایش، پیدا کردن دکمه، ورود به BBB
├── login_setup.py        # ورود یک‌باره به LMS (→ state.json)
├── login_setup_nima.py   # ورود یک‌باره به NIMA (→ state_nima.json)
├── schedule.yaml         # برنامه کلاسی هفتگی شما
├── state.json            # (خودکار) کوکی‌های نشست LMS
├── state_nima.json       # (خودکار) کوکی‌های نشست NIMA
├── .env                  # اطلاعات ورود (این فایل را commit نکنید)
├── requirements.txt      # وابستگی‌های پایتون
└── README.md
```

## عیب‌یابی

| مشکل | راه‌حل |
|------|--------|
| `state.json not found` | ابتدا `python login_setup.py` را اجرا کنید |
| `state_nima.json not found` | ابتدا `python login_setup_nima.py` را اجرا کنید |
| `Session expired` | نشست کاربری منقضی شده — اسکریپت ورود مربوطه را دوباره اجرا کنید |
| دکمه پیدا نمی‌شود / رفرش مداوم | ممکن است کلاس روی پلتفرمی که تنظیم کرده‌اید نباشد. فیلد `platform` در `schedule.yaml` را بررسی کنید |
| NIMA: ردیف پیدا شد ولی نام تطبیق نداد | نام `name` در YAML باید حداقل ۶ حرف اول نام نمایش داده شده در NIMA را داشته باشد |
| مodal صوتی BBB نمایش داده نمی‌شود | BBB ممکن است کند بارگذاری شود. ابزار تا ۶۰ ثانیه صبر می‌کند. اگر خطا داد، اسکرین‌شات به نام `error_<نام_درس>.png` ذخیره می‌شود |
| مرورگر بسته می‌شود | مرورگر اکنون تا ۵ دقیقه بعد از ساعت `end` باز می‌ماند. اگر بسته شد، ممکن است پروسه توسط سیستم خاتمه یافته باشد. خروجی ترمینال را بررسی کنید |