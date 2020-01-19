package net.dankito.jpa.migration

import net.dankito.jpa.DatabaseTestUtil
import net.dankito.jpa.migration.model.Person
import net.dankito.jpa.migration.model.previous.PreviousPerson
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test

class MigratorTest {

    private val testUtil = DatabaseTestUtil()


    private val underTest = Migrator(testUtil.database)


    @After
    fun tearDown() {
        testUtil.cleanUp()
    }


    @Test
    fun migrateClass() {

        // given
        val personDao = testUtil.getDao(Person::class.java)!!
        val previousPersonDao = testUtil.getDao(PreviousPerson::class.java)!!

        previousPersonDao.create(PreviousPerson("Previous Person 1"))
        previousPersonDao.create(PreviousPerson("Previous Person 2"))


        // when
        underTest.migrateClass(PreviousPerson::class.java, Person::class.java)


        // then
        val newPersons = personDao.retrieveAllEntitiesOfType(Person::class.java)
        assertThat(newPersons).hasSize(2)

        val previousPersons = previousPersonDao.retrieveAllEntitiesOfType(PreviousPerson::class.java)
        assertThat(previousPersons).isEmpty()
    }
}