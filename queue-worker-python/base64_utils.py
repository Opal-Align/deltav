"""Base64 encoding utilities for outgoing data."""

from base64 import b64encode, b64decode


def encode_string(data: str) -> str:
    """Encode a string to Base64."""
    return b64encode(data.encode('utf-8')).decode('utf-8')


def encode_bytes(data: bytes) -> str:
    """Encode bytes to Base64 string."""
    return b64encode(data).decode('utf-8')


def encode_json(data: dict) -> str:
    """Encode a dictionary as JSON then Base64."""
    import json
    json_str = json.dumps(data)
    return b64encode(json_str.encode('utf-8')).decode('utf-8')


def decode_string(data: str) -> str:
    """Decode a Base64 string to plain text."""
    return b64decode(data).decode('utf-8')


def decode_to_json(data: str) -> dict:
    """Decode a Base64 string to a dictionary."""
    import json
    return json.loads(b64decode(data).decode('utf-8'))


def update_csv_rowkeys(input_file: str, output_file: str = None):
    """Update RowKey column in CSV with base64 encoded client_id:practice_id."""
    import csv

    if output_file is None:
        output_file = input_file

    rows = []
    with open(input_file, 'r', newline='') as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames
        for row in reader:
            client_id = row['client_id']
            practice_id = row['practice_id']
            row_key = f"{client_id}:{practice_id}"
            row['RowKey'] = encode_string(row_key)
            rows.append(row)

    with open(output_file, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Updated {len(rows)} rows in {output_file}")


def convert_metadata_csv(input_file: str, output_file: str):
    """Convert source metadata CSV to PracticeMetadata.csv format with base64 RowKeys."""
    import csv

    rows = []
    with open(input_file, 'r', newline='') as f:
        reader = csv.DictReader(f)
        for row in reader:
            client_id = row['ClientID']
            practice_id = row['PracticeID']
            row_key = f"{client_id}:{practice_id}"

            rows.append({
                'PartitionKey': 'metadata',
                'RowKey': encode_string(row_key),
                'client_id': client_id,
                'client_id@type': 'String',
                'practice_id': practice_id,
                'practice_id@type': 'String',
                'logo_name': row['practice_logo'],
                'logo_name@type': 'String',
                'is_active': row['is_active'],
                'is_active@type': 'Int32'
            })

    fieldnames = ['PartitionKey', 'RowKey', 'client_id', 'client_id@type',
                  'practice_id', 'practice_id@type', 'logo_name', 'logo_name@type',
                  'is_active', 'is_active@type']

    with open(output_file, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Converted {len(rows)} rows to {output_file}")


def generate_trace_urls(input_file: str, output_file: str):
    """Generate document with trace URLs and practice info."""
    import csv
    import re

    def extract_practice_name(logo_name: str) -> str:
        """Extract practice name from logo_name like '101:10115:easthamiltonlogo.png'."""
        parts = logo_name.split(':')
        if len(parts) >= 3:
            name = parts[2]
            # Remove 'logo.png' or 'logo' suffix
            name = re.sub(r'logo\.png$', '', name, flags=re.IGNORECASE)
            name = re.sub(r'\.png$', '', name, flags=re.IGNORECASE)
            # Convert camelCase to Title Case with spaces
            name = re.sub(r'([a-z])([A-Z])', r'\1 \2', name)
            # Capitalize first letter of each word
            return name.title()
        return logo_name

    rows = []
    with open(input_file, 'r', newline='') as f:
        reader = csv.DictReader(f)
        for row in reader:
            practice_name = extract_practice_name(row['logo_name'])
            rows.append({
                'practice_id': row['practice_id'],
                'practice_name': practice_name,
                'trace_url': f"https://trace.opalalign.com/{row['RowKey']}"
            })

    # Sort by practice_id
    rows.sort(key=lambda x: int(x['practice_id']))

    with open(output_file, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['practice_id', 'practice_name', 'trace_url'])
        writer.writeheader()
        writer.writerows(rows)

    print(f"Generated {len(rows)} trace URLs in {output_file}")


def test_trace_urls(input_file: str):
    """Test all trace URLs and report their status."""
    import csv
    import urllib.request
    import urllib.error

    results = []
    with open(input_file, 'r', newline='') as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    print(f"Testing {len(rows)} URLs...\n")

    for row in rows:
        practice_id = row['practice_id']
        practice_name = row['practice_name']
        url = row['trace_url']

        try:
            req = urllib.request.Request(url, method='HEAD')
            req.add_header('User-Agent', 'Mozilla/5.0')
            response = urllib.request.urlopen(req, timeout=10)
            status = response.getcode()
            status_text = "OK"
        except urllib.error.HTTPError as e:
            status = e.code
            status_text = "ERROR"
        except urllib.error.URLError as e:
            status = 0
            status_text = f"FAIL: {e.reason}"
        except Exception as e:
            status = 0
            status_text = f"FAIL: {e}"

        results.append({
            'practice_id': practice_id,
            'practice_name': practice_name,
            'status': status,
            'result': status_text
        })

        icon = "✓" if status == 200 else "✗"
        print(f"{icon} {practice_id:6} {practice_name:30} {status} {status_text}")

    # Summary
    success = sum(1 for r in results if r['status'] == 200)
    failed = len(results) - success
    print(f"\n{'='*60}")
    print(f"Total: {len(results)} | Success: {success} | Failed: {failed}")

    return results


if __name__ == '__main__':
    test_trace_urls('TraceURLs.csv')