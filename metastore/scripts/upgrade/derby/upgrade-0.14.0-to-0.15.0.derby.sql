-- Upgrade MetaStore schema from 0.14.0 to 0.15.0

RUN '020-HIVE-9296.derby.sql';

UPDATE "APP".VERSION SET SCHEMA_VERSION='0.15.0', VERSION_COMMENT='Hive release version 0.15.0' where VER_ID=1;
