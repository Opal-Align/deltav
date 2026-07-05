-- SQL Server schema for PatientRegistrations table
-- Run this script to create the table before deploying the queue processor

CREATE TABLE PatientRegistrations (
    id NVARCHAR(36) PRIMARY KEY,
    practice_id NVARCHAR(100) NOT NULL,
    registrant NVARCHAR(50),
    patient_type NVARCHAR(50),
    first_name NVARCHAR(100) NOT NULL,
    last_name NVARCHAR(100) NOT NULL,
    dob NVARCHAR(20),
    confirm_accurate BIT NOT NULL DEFAULT 0,
    agree_privacy BIT NOT NULL DEFAULT 0,
    redirect_url NVARCHAR(500),
    relationship NVARCHAR(100),
    relationship_other NVARCHAR(200),
    submitted_at DATETIME2 NOT NULL DEFAULT GETUTCDATE(),
    created_at DATETIME2 NOT NULL DEFAULT GETUTCDATE()
);

-- Index for practice_id lookups
CREATE INDEX IX_PatientRegistrations_PracticeId
ON PatientRegistrations(practice_id);

-- Index for submitted_at for time-based queries
CREATE INDEX IX_PatientRegistrations_SubmittedAt
ON PatientRegistrations(submitted_at DESC);