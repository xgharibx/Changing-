-- Machine-readable reasons a change is not like-for-like; clients localise them. `note` stays for operators.
ALTER TABLE change_event ADD COLUMN reasons text[] NOT NULL DEFAULT '{}';
