-- The original seed is convenient for local smoke tests but must never remain active
-- by default in a production profile. A rotated password is deliberately preserved.
UPDATE admin_user
SET password_hash = '${seed_super_password_hash}',
    status = '${seed_super_status}',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1
  AND username = 'Super'
  AND password_hash = '$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i';
