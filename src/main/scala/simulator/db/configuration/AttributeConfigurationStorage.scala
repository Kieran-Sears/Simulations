package simulator.db.configuration

import java.util.UUID

import cats.effect.IO
import doobie.implicits._
import simulator.model.AttributeConfig
import simulator.db.Storage
import doobie.postgres._
import doobie.postgres.implicits._
import simulator.db.model.AttributeConfigData

class AttributeConfigurationStorage(override val tableName: String) extends Storage {

  def init(): IO[Int] = {
    logger.info(s"Initiliasing Database Table $tableName at $dbUrl")
    for {
      queryResult <- (sql"""
      CREATE TABLE IF NOT EXISTS """ ++ tableNameFragment ++
        sql""" (
        id UUID PRIMARY KEY NOT NULL UNIQUE,
        configuration_id  UUID NOT NUll,
        customer_configuration_id UUID NOT NULL,
        name text NOT NULL,
        value UUID NOT NULL,
        attribute_type text NOT NULL
      );
      CREATE INDEX IF NOT EXISTS """ ++ indexName("to") ++ sql" ON " ++ tableNameFragment ++
        sql""" (id);
    """).update.run.transact(xa)
    } yield queryResult
  }

  def readByCustomerId(id: UUID) = {
    println("attC store read")
    (sql"SELECT id, name, value, attribute_type FROM " ++ tableNameFragment ++ sql" WHERE customer_configuration_id = $id")
      .query[AttributeConfigData]
      .to[List]
      .transact(xa)
  }

  def write(model: AttributeConfig, configurationId: UUID, customerId: UUID) =
    (sql"""INSERT INTO """ ++ tableNameFragment ++
      sql""" (id, configuration_id, customer_configuration_id, name, value, attribute_type)
          VALUES (${model.id}, $configurationId, $customerId, ${model.name}, ${model.value}, ${model.attributeType})
          ON CONFLICT ON CONSTRAINT """ ++ indexName("pkey") ++
      sql""" DO NOTHING""").update.run
      .transact(xa)
}
