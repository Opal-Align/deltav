# Practice Metadata Setup Guide

This document explains how to configure practice metadata in Azure Table Storage for the DeltaV patient registration system.

## Table Configuration

### Table Name

Default: `PracticeMetadata`

Override via environment variable:
```
PRACTICE_METADATA_TABLE=YourCustomTableName
```

## Table Schema

| Column Name      | Type    | Required | Description                                                    |
|------------------|---------|----------|----------------------------------------------------------------|
| PartitionKey     | String  | Yes      | Azure Table partition key (can use a constant like "practice") |
| RowKey           | String  | Yes      | Base64 encoded `clientId:practiceId` (8 characters)            |
| client_id        | String  | Yes      | Client identifier                                              |
| practice_id      | String  | Yes      | Practice identifier                                            |
| practice_name    | String  | Yes      | Display name shown on the registration form                    |
| sms_from_number  | String  | No       | Phone number for SMS notifications                             |
| logo_name        | String  | No       | Logo filename in Azure Blob Storage (e.g., `acme.png`)         |
| is_active        | Integer | Yes      | `1` = active, `0` = inactive                                   |

## RowKey Generation

The RowKey is a Base64 encoded string of `clientId:practiceId`, truncated to 8 characters.

### Formula

```
RowKey = Base64Encode(clientId + ":" + practiceId).substring(0, 8)
```

### Example (Python)

```python
import base64

def generate_rowkey(client_id, practice_id):
    combined = f"{client_id}:{practice_id}"
    encoded = base64.b64encode(combined.encode()).decode()
    return encoded[:8]

# Examples:
# generate_rowkey("ACME", "1001")  -> "QUNNRTox"
# generate_rowkey("BETA", "2002")  -> "QkVUQToy"
```

### Example (Java)

```java
import java.util.Base64;

public static String generateRowKey(String clientId, String practiceId) {
    String combined = clientId + ":" + practiceId;
    String encoded = Base64.getEncoder().encodeToString(combined.getBytes());
    return encoded.substring(0, 8);
}
```

## URL Format

The RowKey becomes the URL path for accessing the registration form:

```
https://trace.opalalign.com/{RowKey}
```

Example: If RowKey is `QUNNRTox`, the URL is `https://trace.opalalign.com/QUNNRTox`

## Example Table Data

| PartitionKey | RowKey   | client_id | practice_id | practice_name       | sms_from_number | logo_name     | is_active |
|--------------|----------|-----------|-------------|---------------------|-----------------|---------------|-----------|
| practice     | QUNNRTox | ACME      | 1001        | Acme Dental Clinic  | +15551234567    | acme_logo.png | 1         |
| practice     | QkVUQToy | BETA      | 2002        | Beta Healthcare     | +15559876543    | beta.png      | 1         |
| practice     | R0FNTUE6 | GAMMA     | 3003        | Gamma Medical       |                 |               | 0         |

## Adding a New Practice

1. Calculate the RowKey: `Base64Encode(clientId:practiceId)` truncated to 8 characters
2. Add a row to the `PracticeMetadata` table with all required fields
3. Set `is_active = 1`
4. Share the URL `https://trace.opalalign.com/{RowKey}` with the practice

## Notes

- Only records with `is_active = 1` are loaded by the application
- Metadata is cached for 10 minutes and refreshed automatically
- Setting `is_active = 0` deactivates a practice without deleting data