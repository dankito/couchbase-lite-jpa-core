package net.dankito.jpa.entitymanager


interface IEntityManager {

    fun getDatabasePath(): String


    fun open(configuration: EntityManagerConfiguration)

    fun optimizeDatabase()

    fun close()


    fun persistEntity(entity: Any): Boolean

    fun updateEntity(entity: Any): Boolean
    fun updateEntities(entities: List<Any>): Boolean

    fun saveOrUpdate(entity: Any): Boolean
    fun saveOrUpdate(entities: List<Any>): Boolean

    fun deleteEntity(entity: Any): Boolean

    fun <T> getEntityById(type: Class<T>, id: String): T?
    fun <T> getEntitiesById(type: Class<T>, ids: Collection<String>, keepOrderingOfIds: Boolean): List<T>
    fun <T> getAllEntitiesOfType(type: Class<T>): List<T>

}
