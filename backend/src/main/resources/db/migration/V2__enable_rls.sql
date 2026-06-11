-- V2: Enable Row Level Security natively

ALTER TABLE properties ENABLE ROW LEVEL SECURITY;
ALTER TABLE units ENABLE ROW LEVEL SECURITY;
ALTER TABLE leases ENABLE ROW LEVEL SECURITY;

-- Property RLS
CREATE POLICY isolate_properties ON properties 
    USING (owner_id = current_setting('app.current_owner', true));

-- Unit RLS
CREATE POLICY isolate_units ON units 
    USING (owner_id = current_setting('app.current_owner', true));

-- Lease RLS
CREATE POLICY isolate_leases ON leases 
    USING (owner_id = current_setting('app.current_owner', true));
