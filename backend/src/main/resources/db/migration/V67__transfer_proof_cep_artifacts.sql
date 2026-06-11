ALTER TABLE transfer_proof_submissions
    ADD COLUMN IF NOT EXISTS cep_xml_url VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS cep_pdf_url VARCHAR(1000);
