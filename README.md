# AUT LMS Auto Joiner

A Python automation tool built with Playwright and APScheduler that automatically logs into the Amirkabir University of Technology (AUT) LMS, waits for your scheduled classes to start, joins the BigBlueButton (BBB) sessions as "listen-only", and automatically closes the browser when the class is over.

## Features
* **Automated Login**: Uses CAS SSO to log into the LMS and saves the session securely locally.
* **Smart Polling**: If the class has not started yet ("هنوز برگزار نشده"), it will wait and refresh the page automatically.
* **Auto-Join BBB**: Automatically clicks the required buttons to join the BigBlueButton audio in listen-only mode.
* **Catch-up Mechanism**: If you start the script while a class is already in progress, it will join immediately.
* **YAML Scheduling**: Easily manage your weekly class schedule using a simple configuration file.
* **Timezone Aware**: Fully configured for Iran Standard Time (Asia/Tehran).

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

### 1. Credentials (.env)
Create a `.env` file in the root directory of the project and add your AUT portal credentials:
```env
LMS_USERNAME=your_student_id
LMS_PASSWORD=your_password
```

### 2. Schedule (schedule.yaml)
Create or edit the `schedule.yaml` file to match your weekly classes. 
Note: The `name` field must exactly match the course name as it appears on the LMS dashboard.

Example:
```yaml
classes:
  - name: "انقلاب اسلامي ايران"
    days: ["sat"]
    start: "10:00"
    end: "12:00"
    
  - name: "شبکه‌هاي کامپيوتري"
    days: ["sat", "mon", "wed"]
    start: "15:00"
    end: "16:30"
```

## Usage

**Step 1: Initial Login**
Run the login setup script once. This will open a browser, log you in, and save your session state to a file named `state.json`.
```bash
python login_setup.py
```

**Step 2: Start the Scheduler**
Once the `state.json` file is created, run the scheduler. Leave this terminal open. It will wait in the background and automatically join classes when it is time.
```bash
python scheduler.py
```

---

# ورود خودکار به کلاس‌های LMS امیرکبیر

یک ابزار اتوماسیون پایتون با استفاده از Playwright و APScheduler که به صورت خودکار وارد سامانه آموزش مجازی دانشگاه صنعتی امیرکبیر (LMS) می‌شود، منتظر زمان شروع کلاس می‌ماند، به عنوان «فقط شنونده» وارد محیط BigBlueButton (BBB) می‌شود و پس از اتمام زمان کلاس مرورگر را می‌بندد.

## ویژگی‌ها
* **ورود خودکار**: ورود از طریق سامانه یکپارچه (SSO) و ذخیره محلی نشست کاربری (Session).
* **بررسی هوشمند وضعیت کلاس**: در صورتی که کلاس هنوز شروع نشده باشد (وضعیت "هنوز برگزار نشده")، ابزار منتظر مانده و صفحه را به صورت خودکار رفرش می‌کند.
* **ورود خودکار به BBB**: به صورت خودکار دکمه‌های لازم برای ورود به محیط صوتی بیگ‌بلو‌باتن (حالت فقط شنونده) را کلیک می‌کند.
* **قابلیت جبران (Catch-up)**: اگر اسکریپت را در حالی اجرا کنید که زمان یک کلاس از قبل شروع شده باشد، ربات بلافاصله وارد آن کلاس می‌شود.
* **زمان‌بندی با فایل YAML**: مدیریت آسان برنامه کلاسی هفتگی به کمک یک فایل تنظیمات ساده.
* **پشتیبانی از منطقه زمانی**: تنظیم شده برای منطقه زمانی ایران (Asia/Tehran).

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

### ۱. اطلاعات ورود (.env)
یک فایل به نام `.env` در مسیر اصلی پروژه ایجاد کنید و اطلاعات ورود به پورتال دانشگاه را در آن قرار دهید:
```env
LMS_USERNAME=شماره_دانشجویی
LMS_PASSWORD=رمز_عبور
```

### ۲. برنامه کلاسی (schedule.yaml)
فایل `schedule.yaml` را مطابق با کلاس‌های خود ویرایش کنید.
توجه: مقداری که در فیلد `name` قرار می‌دهید باید دقیقا مشابه نام درس در داشبورد سامانه LMS باشد.

نمونه:
```yaml
classes:
  - name: "انقلاب اسلامي ايران"
    days: ["sat"]
    start: "10:00"
    end: "12:00"
    
  - name: "شبکه‌هاي کامپيوتري"
    days: ["sat", "mon", "wed"]
    start: "15:00"
    end: "16:30"
```

## نحوه استفاده

**مرحله اول: ورود اولیه**
ابتدا اسکریپت زیر را یک بار اجرا کنید. این کار مرورگر را باز کرده، شما را لاگین می‌کند و وضعیت ورود شما را در فایلی به نام `state.json` ذخیره می‌کند.
```bash
python login_setup.py
```

**مرحله دوم: اجرای زمان‌بند**
پس از ایجاد فایل `state.json`، زمان‌بند را اجرا کنید. این ترمینال را باز بگذارید تا ربات در پس‌زمینه منتظر بماند و در زمان‌های مقرر وارد کلاس‌ها شود.
```bash
python scheduler.py
```