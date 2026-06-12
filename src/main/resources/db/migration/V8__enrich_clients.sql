ALTER TABLE clients ADD COLUMN category        VARCHAR(40);
ALTER TABLE clients ADD COLUMN sector          VARCHAR(120);
ALTER TABLE clients ADD COLUMN website         VARCHAR(254);
ALTER TABLE clients ADD COLUMN address_line    VARCHAR(254);
ALTER TABLE clients ADD COLUMN city            VARCHAR(120);
ALTER TABLE clients ADD COLUMN postal_code     VARCHAR(20);
ALTER TABLE clients ADD COLUMN country         VARCHAR(80);
ALTER TABLE clients ADD COLUMN relationship_manager_id UUID REFERENCES users(id);
