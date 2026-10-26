CREATE TABLE IF NOT EXISTS batch_page_runs (
    batch_id VARCHAR(64) NOT NULL,
    page_id INTEGER NOT NULL,
    success_count INTEGER NOT NULL,
    failure_count INTEGER NOT NULL,
    PRIMARY KEY (batch_id, page_id)
);

CREATE TABLE IF NOT EXISTS imported_records (
    batch_id VARCHAR(64) NOT NULL,
    record_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (batch_id, record_id)
);

CREATE TABLE IF NOT EXISTS rejected_rows (
    batch_id VARCHAR(64) NOT NULL,
    page_id INTEGER NOT NULL,
    line_number INTEGER NOT NULL,
    reason TEXT NOT NULL,
    raw_row TEXT NOT NULL,
    PRIMARY KEY (batch_id, page_id, line_number)
);
