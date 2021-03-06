package simulator.db

import java.util.UUID

import cats.effect.IO
import simulator.db.ml._
import simulator.model._
import cats.implicits._
import simulator.db.model.{AttributeConfigData, CategoricalConfigData, OptionConfigData}

trait StorageError

trait StorageController {
  def initialiseStorageTables(): IO[Either[StorageError, Unit]]
  def initialiseTrainingTables(): IO[Either[StorageError, Unit]]
  def initialisePlayTables(tableName: String, attributes: List[AttributeConfig]): IO[Either[StorageError, Unit]]
  def storeConfiguration(username: String, config: Configurations): IO[Either[StorageError, Unit]]
  def getConfiguration(configId: UUID): IO[Either[StorageError, Configurations]]
  def storeTrainingData(data: TrainingData): IO[Either[StorageError, Unit]]
  def storePlayingData(
    tableName: String,
    attributes: List[AttributeConfig],
    data: List[(simulator.model.Customer, simulator.model.Action)],
    configurationId: UUID,
    labels: Map[String, Int]): IO[Either[StorageError, Unit]]
}

class StorageImpl extends StorageController {
  val c = "configuration_"
  val simStorage: configuration.Simulation = new configuration.Simulation(c + "simulation")
  val cusStorage: configuration.Customer = new configuration.Customer(c + "customer")
  val actStorage: configuration.Action = new configuration.Action(c + "action")
  val effStorage: configuration.Effect = new configuration.Effect(c + "effect")
  val oveStorage: configuration.AttributeOverride = new configuration.AttributeOverride(c + "attribute_override")
  val gloStorage: configuration.AttributeGlobal = new configuration.AttributeGlobal(c + "attribute_global")
  val scaStorage: configuration.Scalar = new configuration.Scalar(c + "scalar")
  val catStorage: configuration.Categorical = new configuration.Categorical(c + "categorical")
  val optStorage: configuration.Option = new configuration.Option(c + "option")

  val t = "training_"
  val attributeStorage: training.Attribute = new training.Attribute(t + "attribute")
  val customerStorage: training.Customer = new training.Customer(t + "customer")
  val actionStorage: training.Action = new training.Action(t + "action")
  val effectStorage: training.Effect = new training.Effect(t + "effect")

  val train: TrainStorage = new TrainStorage("playing_train")
  // val test: TestStorage = new TestStorage("playing_test")

  override def initialiseStorageTables(): IO[Either[StorageError, Unit]] = {
    println("initialiseStorageTables")

    for {
      _ <- simStorage.init()
      _ <- cusStorage.init()
      _ <- actStorage.init()
      _ <- effStorage.init()
      _ <- oveStorage.init()
      _ <- gloStorage.init()
      _ <- scaStorage.init()
      _ <- catStorage.init()
      _ <- optStorage.init()
    } yield Right(Unit)
  }

  override def initialiseTrainingTables(): IO[Either[StorageError, Unit]] = {
    for {
      _ <- attributeStorage.init()
      _ <- customerStorage.init()
      _ <- actionStorage.init()
      _ <- effectStorage.init()
    } yield Right(Unit)
  }

  override def initialisePlayTables(
    tableName: String,
    attributes: List[AttributeConfig]): IO[Either[StorageError, Unit]] = {
    println("initialisePlayTables")
    for {
      _ <- train.init(tableName, attributes)
    } yield Right(Unit)
  }

  def storeAttributeValues(attribute: AttributeConfig, config: Configurations): IO[List[Int]] = {
    val valueList = config.scalarConfigurations ++ config.categoricalConfigurations
    val values = valueList.filter(_.id == attribute.value)

    for {
      a <- values.map {
        case scalar: ScalarConfig =>
          scaStorage.write(scalar, config.id, attribute.id)
        case categorical: CategoricalConfig =>
          catStorage.write(categorical, config.id, attribute.id)
      }.sequence
      b <- values.collect {
        case categorical: CategoricalConfig => storeCategoricalOptions(categorical, config, attribute)
      }.flatten.sequence
    } yield List(1)

  }

  def storeCategoricalOptions(categorical: CategoricalConfig, config: Configurations, attribute: AttributeConfig) = {
    categorical.options.flatMap(y => {
      config.optionConfigurations
        .filter(_.id == y)
        .map(option => optStorage.write(option, config.id, categorical.id))
    })
  }

  def storeAttributesByCustomer(customerId: UUID, attributes: List[UUID], config: Configurations): IO[List[Int]] = {
    val atts = attributes.flatMap(a => config.attributeConfigurations.find(_.id == a))
    for {
      _ <- atts.map(attribute => oveStorage.write(attribute, config.id, customerId)).sequence
      _ <- atts.map(attribute => storeAttributeValues(attribute, config)).sequence
    } yield List(1)
  }

  def storeEffectsByAction(config: Configurations): IO[List[Int]] = {
    config.actionConfigurations
      .map(action => {
        action.effectConfigurations.map(y => {
          val effects = config.effectConfigurations.filter(z => z.id == y)
          effects.map(effect => effStorage.write(effect, config.id, action.id))
        })
        actStorage.write(action, config.id)
      })
      .sequence
  }

  def storeGlobalAttributes(config: Configurations) = {
    val attributes = config.attributeConfigurations
      .filter(_.attributeType == AttributeEnum.Global)
    for {
      _ <- attributes.map(attribute => gloStorage.write(attribute, config.id)).sequence
      _ <- attributes.map(attribute => storeAttributeValues(attribute, config)).sequence
    } yield List(1)

  }

  override def storeConfiguration(username: String, config: Configurations): IO[Either[StorageError, Unit]] = {
    for {
      _ <- simStorage.write(config.simulationConfiguration, config.id)
      _ <- config.customerConfigurations.map(c => cusStorage.write(c, config.id)).sequence
      _ <- config.customerConfigurations
        .map(c => storeAttributesByCustomer(c.id, c.attributeOverrides, config))
        .sequence
      _ <- storeGlobalAttributes(config)
      _ <- storeEffectsByAction(config)
    } yield Right(Unit)
  }

  def getPrimaryConfigurations(configId: UUID): IO[(SimulationConfig, List[ActionConfig], List[CustomerConfig])] = {
    for {
      simulation <- simStorage.readByOwnerId(configId)
      cd <- cusStorage.readByOwnerId(configId)
      customers <- IO(cd.map(x => CustomerConfig(x.id, x.name, Nil, x.proportion)))
      ad <- actStorage.readByOwnerId(configId)
      actions <- IO(ad.map(x => ActionConfig(x.id, x.name, x.actionType, Nil)))
    } yield (simulation, actions, customers)
  }

  def loadEffectIdsIntoAction(
    actions: List[ActionConfig],
    effects: List[List[EffectConfig]]): IO[List[ActionConfig]] = {
    IO(actions.zip(effects).map {
      case (action: ActionConfig, effects: List[EffectConfig]) => action.copy(effectConfigurations = effects.map(_.id))
    })
  }

  def loadAttributesIntoCustomer(
    customers: List[CustomerConfig],
    attributes: List[List[AttributeConfigData]]): IO[List[CustomerConfig]] = {
    IO(customers.zip(attributes).map {
      case (customer: CustomerConfig, attributes: List[AttributeConfigData]) =>
        customer.copy(attributeOverrides = attributes.map(_.id))
    })
  }

  def loadOptionsIntoCategorical(
    categoricals: List[CategoricalConfigData],
    options: List[List[OptionConfigData]]): IO[List[CategoricalConfig]] = {
    IO(categoricals.zip(options).map {
      case (categorical: CategoricalConfigData, optionList: List[OptionConfigData]) => {
        CategoricalConfig(categorical.id, optionList.map(_.id))
      }
    })
  }

  override def getConfiguration(configId: UUID): IO[Either[StorageError, Configurations]] = {
    val (simulation, rawActions, rawCustomers) = getPrimaryConfigurations(configId).unsafeRunSync()
    for {
      effects <- rawActions.map(action => effStorage.readByOwnerId(action.id)).sequence
      actions <- loadEffectIdsIntoAction(rawActions, effects)
      overrideAtts <- rawCustomers
        .map(customer => oveStorage.readByOwnerId(customer.id))
        .sequence
      customers <- loadAttributesIntoCustomer(rawCustomers, overrideAtts)
      rawScalars <- overrideAtts.flatten.map(attribute => scaStorage.readByOwnerId(attribute.id)).sequence
      overrideScalars <- IO(rawScalars.map(s => ScalarConfig(s.id, s.variance_type, s.min, s.max)))
      rawCategoricals <- overrideAtts.flatten
        .map(attribute => catStorage.readByOwnerId(attribute.id))
        .sequence
      rawOptions <- rawCategoricals.flatten.map(categorical => optStorage.readByOwnerId(categorical.id)).sequence
      options <- IO(rawOptions.flatten.map(o => OptionConfig(o.id, o.name, o.probability)))
      categoricals <- loadOptionsIntoCategorical(rawCategoricals.flatten, rawOptions)
      globalAtts <- gloStorage.readByOwnerId(configId)
      attributes <- IO((overrideAtts.flatten ++ globalAtts).map(attribute => {
        AttributeConfig(attribute.id, attribute.name, attribute.value, attribute.attributeType)
      }))
    } yield
      Right(
        Configurations(
          configId,
          customers,
          actions,
          effects.flatten,
          attributes,
          overrideScalars,
          categoricals,
          options,
          simulation))
  }

  override def storeTrainingData(data: TrainingData): IO[Either[StorageError, Unit]] = {
    for {
      _ <- data.actions
        .flatMap(action => action.effects.map(effect => effectStorage.write(effect, data.configurationId, action.id)))
        .sequence
      _ <- data.customers
        .flatMap(customer =>
          customer.attributes.map(attribute => attributeStorage.write(attribute, data.configurationId, customer.id)))
        .sequence
      _ <- data.actions.map(x => actionStorage.write(x, data.configurationId)).sequence
      _ <- data.customers.map(x => customerStorage.write(x, data.configurationId)).sequence
    } yield Right(Unit)
  }

  override def storePlayingData(
    tableName: String,
    attributes: List[AttributeConfig],
    data: List[(simulator.model.Customer, simulator.model.Action)],
    configurationId: UUID,
    labels: Map[String, Int]): IO[Either[StorageError, Unit]] = {
    val globalAttributes = attributes.filter(att => att.attributeType == AttributeEnum.Global)
    println(s"globalAttributes:\n$globalAttributes")
    for {
      _ <- initialisePlayTables(tableName, globalAttributes)
      _ <- data.map {
        case (customer, action) => train.write(tableName, customer, labels(action.name), configurationId)
      }.sequence
    } yield Right(Unit)
  }

}
