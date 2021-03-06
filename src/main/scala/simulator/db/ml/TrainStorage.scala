package simulator.db.ml

import java.util.UUID

import cats.effect.IO
import doobie.implicits._
import doobie.util.fragment.Fragment
import simulator.db.Storage
import simulator.model.{AttributeConfig, Customer}
import doobie.postgres._
import doobie.postgres.implicits._

class TrainStorage(override val tableName: String) extends Storage {

  def init(nameExt: String, attributes: List[AttributeConfig]): IO[Int] = {
    logger.info(s"Initiliasing Database Table $tableName at $dbUrl")
    val fields = Fragment.const(attributes.foldLeft("") {
      case (acc, a) => acc + s"${a.name} FLOAT NOT NULL, "
    })

    for {
      queryResult <- (sql"""
      CREATE TABLE IF NOT EXISTS """ ++ Fragment.const(s"$nameExt") ++
        sql""" (
        id UUID PRIMARY KEY UNIQUE,
        configuration_id UUID NOT NUll,
        customer_id UUID NOT NULL,
        """ ++ fields ++ sql"""
        action_label FLOAT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS """ ++ indexName("to") ++ sql" ON " ++ Fragment.const(s"$nameExt") ++
        sql""" (customer_id);
    """).update.run.transact(xa)
    } yield queryResult
  }

  def write(nameExt: String, customer: Customer, actionLabel: Int, configurationId: UUID) = {

    val names =
      Fragment.const(customer.attributes.foldLeft("id, configuration_id, customer_id, ") {
        case (acc, a) => acc + s"${a.name}, "
      } + " action_label")

    val featureValues = Fragment.const(customer.attributes.foldLeft("")((acc, a) => acc ++ s"${a.value}, "))

    val values = Fragment.const(s""" \'${UUID.randomUUID()}\', \'$configurationId\', \'${customer.id}\', """) ++ featureValues ++ Fragment
      .const(s"""\'$actionLabel\'""")

    (sql"""INSERT INTO """ ++ Fragment.const(s"$nameExt") ++ sql""" (""" ++ names ++ sql""")""" ++
      sql""" VALUES (""" ++ values ++ sql""")
          ON CONFLICT ON CONSTRAINT """ ++ Fragment.const(s"${nameExt}_pkey") ++
      sql""" DO NOTHING""").update.run
      .transact(xa)
  }
}
