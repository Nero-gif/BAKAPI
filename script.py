import requests
import os
import json
from dotenv import load_dotenv
from bs4 import BeautifulSoup

# 1. Načtení proměnných ze souboru .env
load_dotenv("/home/nero/CTF/tohleSemNepatri/.env")

BASE_URL = "https://bakalari.infis.cz"
USERNAME = os.getenv("BAKA_USER")
PASSWORD = os.getenv("BAKA_PASS")

def get_marks_secure():
    # Kontrola, jestli se údaje načetly
    if not USERNAME or not PASSWORD:
        print("❌ Chyba: Proměnné BAKA_USER nebo BAKA_PASS nebyly v .env nalezeny.")
        return

    session = requests.Session()
    # Simulujeme moderní prohlížeč
    session.headers.update({
        'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    })

    try:
        print(f"Connecting to {BASE_URL}...")
        login_page = session.get(f"{BASE_URL}/login")
        login_page.raise_for_status()

        login_data = {
            'username': USERNAME,
            'password': PASSWORD,
            'returnUrl': ''
        }
        
        print(f"Logging in as {USERNAME}...")
        login_res = session.post(f"{BASE_URL}/Login", data=login_data, allow_redirects=True)
        login_res.raise_for_status()

        print("✅ Login odeslán. Pokouším se stáhnout průběžnou klasifikaci...")

        grades_url = f"{BASE_URL}/next/prubzna.aspx"
        headers = {
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'X-Requested-With': 'XMLHttpRequest',
            'Referer': f'{BASE_URL}/dashboard'
        }

        response = session.get(grades_url, headers=headers)
        response.raise_for_status()

        if "login" in response.url.lower():
            print("❌ Server nás po přihlášení poslal zpět na login. Zkontroluj přihlašovací údaje nebo změny Bakalářů.")
            return

        soup = BeautifulSoup(response.text, 'html.parser')
        marks = soup.select('.predmet-radek .znamka-v, .predmet-radek .znamka-h')

        if not marks:
            print("⚠️ Nepodařilo se najít žádné známky na stránce průběžné klasifikace.")
            print(f"URL: {response.url}")
            return

        print("\n" + "=" * 30)
        print("   PŘEHLED ZNÁMEK")
        print("=" * 30)

        current_subject = None
        for mark in marks:
            data = json.loads(mark.get('data-clasif', '{}'))
            subject = data.get('nazev', 'Neznámý předmět')
            mark_text = data.get('MarkText', '?')
            caption = data.get('caption') or 'Bez tématu'
            date = data.get('strdatum') or 'Bez data'
            weight = data.get('vaha')
            note = data.get('poznamkakzobrazeni') or ''

            if subject != current_subject:
                current_subject = subject
                print(f"\n{subject}:")

            detail = f"[{mark_text}] {caption}"
            if note:
                clean_note = BeautifulSoup(note, 'html.parser').get_text(' ', strip=True)
                if clean_note:
                    detail += f" - {clean_note}"
            if weight:
                detail += f" (váha {weight})"
            detail += f" - {date}"
            print(f"  • {detail}")

    except Exception as e:
        print(f"❌ Neočekávaná chyba: {e}")

if __name__ == '__main__':
    get_marks_secure()