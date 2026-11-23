#!/usr/bin/env python3
# coding: utf-8
"""Conservative, reversible ADB assistant for DEXP / YaOS.
Read-only by default; no blind debloat, no firmware flashes, no factory menu access.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
STATE = ROOT / 'changed_packages.json'

# Only two optional packages are initially approved for the opt-in pilot.
# These package names MUST be checked against the actual TV inventory first.
PILOT = {
    'com.yandex.android.advid': 'Рекламный идентификатор/сервис, не основной экран',
    'com.yandex.tv.bugreportsender': 'Отправка отчётов об ошибках',
}
# Protection is intentional, even for users who know --package.
PROTECTED = {
    'com.yandex.tv.home', 'com.yandex.tv.services.platform',
    'com.yandex.tv.services', 'com.yandex.tv.live',
    'com.yandex.tv.input.efir', 'com.yandex.tv.settings',
    'ru.yandex.androidkeyboard.tv', 'com.yandex.io.sdk',
    'com.android.systemui', 'com.android.settings',
    'com.android.bluetooth', 'android', 'com.android.packageinstaller',
    'com.android.providers.settings', 'com.android.providers.media',
}


def adb(*args, timeout=40):
    p = subprocess.run(['adb', *args], stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                       errors='replace', timeout=timeout)
    if p.returncode:
        raise RuntimeError('ADB отказал: ' + p.stdout.strip())
    return p.stdout.strip()


def adb_shell(*args, timeout=40):
    return adb('shell', *args, timeout=timeout)


def adb_ready():
    if not shutil.which('adb'):
        sys.exit('Не найден adb. Сначала установите Android Platform Tools на компьютер.')
    try:
        state = adb('get-state', timeout=10)
        if state != 'device':
            sys.exit('ADB не подключён. На телевизоре необходимо разрешить отладку.')
    except (RuntimeError, subprocess.TimeoutExpired) as err:
        sys.exit('Нет подключённого телевизора: ' + str(err))


def packages(disabled=False):
    args = ('pm', 'list', 'packages', '-d') if disabled else ('pm', 'list', 'packages')
    return {line[len('package:'):].strip() for line in adb_shell(*args).splitlines()
            if line.startswith('package:')}


def print_report():
    sections = [
        ('Модель', ('getprop', 'ro.product.model')),
        ('Прошивка', ('getprop', 'ro.build.display.id')),
        ('Версия Android', ('getprop', 'ro.build.version.release')),
        ('Плата', ('getprop', 'ro.product.board')),
        ('Оперативная память', ('cat', '/proc/meminfo')),
        ('Раздел данных', ('df', '-h', '/data')),
        ('Домашние приложения', ('cmd', 'package', 'query-activities', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')),
        ('Пакеты', ('pm', 'list', 'packages')),
        ('Отключённые пакеты', ('pm', 'list', 'packages', '-d')),
        ('Потребление памяти', ('dumpsys', 'meminfo')),
    ]
    report = ['DEXP Lite - диагностика только чтение', time.strftime('%Y-%m-%d %H:%M:%S')]
    for title, cmd in sections:
        report.append('\n### ' + title)
        try:
            report.append(adb_shell(*cmd, timeout=60))
        except (RuntimeError, subprocess.TimeoutExpired) as err:
            report.append('Недоступно: ' + str(err))
    filename = ROOT / ('tv_report_' + time.strftime('%Y%m%d_%H%M%S') + '.txt')
    filename.write_text('\n'.join(report), encoding='utf-8')
    print('Отчёт сохранён: ' + str(filename))
    print('Внимание: перед пересылкой проверьте отчёт на наличие личных данных.')
    print('Телевизор не изменён.')


def review():
    all_pkgs = packages()
    disabled_pkgs = packages(disabled=True)
    print('Найдено пакетов:', len(all_pkgs))
    print('\nВозможные кандидаты на отключение (не автоматически):')
    for pkg, info in PILOT.items():
        if pkg in all_pkgs:
            status = 'уже выключен' if pkg in disabled_pkgs else 'включён'
            print('  {} [{}] — {}'.format(pkg, status, info))
        else:
            print('  {} [нет на ТВ]'.format(pkg))
    print('\nЗащищённые компоненты из стоп-листа, обнаруженные на ТВ:')
    for pkg in sorted(PROTECTED.intersection(all_pkgs)):
        print('  ', pkg)
    print('\nБез согласования другие пакеты лучше не отключать.')


def save_state(past):
    STATE.write_text(json.dumps(past, ensure_ascii=False, indent=2), encoding='utf-8')


def load_state():
    if not STATE.exists():
        return {'disabled_by_tool': []}
    return json.loads(STATE.read_text(encoding='utf-8'))


def disable(pkg):
    if pkg in PROTECTED or pkg not in PILOT:
        sys.exit('Этот пакет не разрешён к отключению. Нет команды для удаления системных компонентов.')
    all_pkgs = packages()
    if pkg not in all_pkgs:
        sys.exit('На этом телевизоре пакет отсутствует.')
    if pkg in packages(disabled=True):
        print('Уже выключен. Изменений нет.')
        return
    print('Будет отключён только для пользователя 0:', pkg)
    print('Системные файлы не удаляются, но поведение ТВ может измениться.')
    if input('Для продолжения напишите ОТКЛЮЧИТЬ: ').strip() != 'ОТКЛЮЧИТЬ':
        sys.exit('Отменено.')
    # Save rollback intent BEFORE modification, to survive loss of ADB connection.
    state = load_state()
    changes = set(state.get('disabled_by_tool', []))
    changes.add(pkg)
    state['disabled_by_tool'] = sorted(changes)
    save_state(state)
    result = adb_shell('pm', 'disable-user', '--user', '0', pkg)
    print(result)
    print('Восстановление: python tv_manager.py restore')


def restore():
    state = load_state()
    changes = state.get('disabled_by_tool', [])
    if not changes:
        print('Нет пакетов, отключённых этой утилитой.')
        return
    failures = []
    for pkg in changes:
        try:
            print(pkg, '=>', adb_shell('pm', 'enable', '--user', '0', pkg))
        except (RuntimeError, subprocess.TimeoutExpired) as err:
            print('Ошибка восстановления {}: {}'.format(pkg, err))
            failures.append(pkg)
    save_state({'disabled_by_tool': failures})
    print('Восстановление завершено. Перезагрузите ТВ, если нужно.')


def main():
    parser = argparse.ArgumentParser(description='DEXP Lite: диагностика и обратимое отключение двух необязательных сервисов')
    parser.add_argument('command', choices=['doctor', 'review', 'disable', 'restore'])
    parser.add_argument('--connect', metavar='TV_IP', help='Например 192.168.1.50 (порт 5555)')
    parser.add_argument('--package', help='Имя пакета для команды disable')
    args = parser.parse_args()
    if not shutil.which('adb'):
        sys.exit('Нет adb в PATH. Установите Android Platform Tools.')
    if args.connect:
        print(adb('connect', args.connect + ':5555', timeout=15))
    adb_ready()
    if args.command == 'doctor':
        print_report()
    elif args.command == 'review':
        review()
    elif args.command == 'disable':
        if not args.package:
            sys.exit('Укажите --package после списка из review')
        disable(args.package)
    elif args.command == 'restore':
        restore()


if __name__ == '__main__':
    main()
