#!/usr/bin/env python3
"""Print per-practice send/fail stats from a send_sms.py sent-log CSV.

Usage:
    python sms_stats.py --log-file sent_log.csv
"""

import argparse
import csv
import os
from collections import defaultdict

DEFAULT_SENT_LOG_PATH = 'sent_log.csv'


def load_stats(log_path: str) -> dict:
    """Returns {(practice_id, practice_name): {'success': n, 'failed': n}}. Empty if log_path doesn't exist."""
    stats = defaultdict(lambda: {'success': 0, 'failed': 0})

    if not os.path.exists(log_path):
        return stats

    with open(log_path, newline='', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            key = (row['practice_id'], row['practice_name'])
            status = row['status']
            if status in ('success', 'failed'):
                stats[key][status] += 1

    return stats


def print_stats(stats: dict):
    rows = sorted(stats.items(), key=lambda item: item[0][1].lower())

    header = f"{'Practice ID':<12} {'Practice Name':<40} {'Success':>8} {'Failed':>8} {'Total':>8}"
    print(header)
    print('-' * len(header))

    total_success, total_failed = 0, 0
    for (practice_id, practice_name), counts in rows:
        success, failed = counts['success'], counts['failed']
        total_success += success
        total_failed += failed
        print(f"{practice_id:<12} {practice_name:<40} {success:>8} {failed:>8} {success + failed:>8}")

    print('-' * len(header))
    print(f"{'TOTAL':<53} {total_success:>8} {total_failed:>8} {total_success + total_failed:>8}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--log-file', default=DEFAULT_SENT_LOG_PATH,
                         help=f'sent_log.csv produced by send_sms.py (default: {DEFAULT_SENT_LOG_PATH})')
    args = parser.parse_args()

    stats = load_stats(args.log_file)
    if not stats:
        print(f"No rows found in {args.log_file}")
        return

    print_stats(stats)


if __name__ == '__main__':
    main()
