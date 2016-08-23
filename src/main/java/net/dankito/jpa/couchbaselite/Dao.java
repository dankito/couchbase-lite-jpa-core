package net.dankito.jpa.couchbaselite;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Document;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.dankito.jpa.annotationreader.config.EntityConfig;
import net.dankito.jpa.annotationreader.config.OrderByConfig;
import net.dankito.jpa.annotationreader.config.PropertyConfig;
import net.dankito.jpa.cache.ObjectCache;
import net.dankito.jpa.cache.RelationshipDaoCache;
import net.dankito.jpa.relationship.collections.EntitiesCollection;
import net.dankito.jpa.relationship.collections.LazyLoadingEntitiesCollection;
import net.dankito.jpa.relationship.collections.ManyToManyEntitiesCollection;
import net.dankito.jpa.util.CrudOperation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.persistence.AccessType;

/**
 * In contrary to JPA standard a bidirectional @OneToMany additionally stores its joined entity ids also on the one side in a String array.
 * The same does an unidirectional @OneToMany (instead of creating a Join Table according to standard).
 *
 * CascadeType.Merge and CascadeType.Detach are currently completely ignored.
 *
 * Created by ganymed on 15/08/16.
 */
public class Dao {

  protected Database database;

  protected EntityConfig entityConfig;

  protected Class entityClass;

  protected ObjectCache objectCache;

  protected RelationshipDaoCache relationshipDaoCache;

  protected ObjectMapper objectMapper = null;


  public Dao(Database database, EntityConfig entityConfig, ObjectCache objectCache, RelationshipDaoCache relationshipDaoCache) {
    this.database = database;
    this.entityConfig = entityConfig;
    this.objectCache = objectCache;
    this.relationshipDaoCache = relationshipDaoCache;

    this.entityClass = entityConfig.getEntityClass();
  }


  public boolean create(Object object) throws SQLException, CouchbaseLiteException {
    if(isAlreadyPersisted(object)) {
      return false;
    }

    entityConfig.invokePrePersistLifeCycleMethod(object);

    Document objectDocument = createEntityInDb(object);

    createCascadePersistPropertiesAndUpdateDocument(object, objectDocument);

    entityConfig.invokePostPersistLifeCycleMethod(object);

    return true;
  }

  protected Document createEntityInDb(Object object) throws SQLException, CouchbaseLiteException {
    checkIfCrudOperationCanBePerformedOnObjectOfClass(object, CrudOperation.CREATE);

    Document newDocument = database.createDocument();

    Map<String, Object> mappedProperties = mapProperties(object, entityConfig, null);

    newDocument.putProperties(mappedProperties);

    setIdOnObject(object, newDocument);
    updateVersionOnObject(object, newDocument);

    objectCache.add(entityClass, newDocument.getId(), object);

    return newDocument;
  }

  protected void createCascadePersistPropertiesAndUpdateDocument(Object object, Document objectDocument) throws SQLException, CouchbaseLiteException {
    Map<String, Object> cascadedProperties = createCascadePersistProperties(object);

    if(cascadedProperties.size() > 0) {
      Map<String, Object> documentProperties = new HashMap<>();
      documentProperties.putAll(objectDocument.getProperties());
      documentProperties.putAll(cascadedProperties);

      objectDocument.putProperties(documentProperties); // TODO: this is bad as in this way in one create() two Revisions get created (but we need to persist object first prior to cascading its Properties)
    }
  }

  protected Map<String, Object> createCascadePersistProperties(Object object) throws SQLException, CouchbaseLiteException {
    Map<String, Object> cascadedProperties = new HashMap<>();

    for(PropertyConfig cascadePersistProperty : entityConfig.getRelationshipPropertiesWithCascadePersist()) {
      Dao targetDao = relationshipDaoCache.getTargetDaoForRelationshipProperty(cascadePersistProperty);
      Object propertyValue = getPropertyValue(object, cascadePersistProperty);

      if(propertyValue != null) {
        if(cascadePersistProperty.isCollectionProperty()) {
          if(cascadePersistProperty.isManyToManyField() == false) {
            persistOneToManyCollectionItems(cascadePersistProperty, targetDao, cascadedProperties, (Collection) propertyValue);
          }
          else {
            persistManyToManyCollectionItems(cascadePersistProperty, targetDao, cascadedProperties, (Collection) propertyValue);
          }

          if(propertyValue instanceof EntitiesCollection == false) {
            createAndSetEntitiesCollectionAndAddExistingItems(object, cascadePersistProperty, propertyValue);
          }
        }
        else if (targetDao.create(propertyValue)) {
          cascadedProperties.put(cascadePersistProperty.getColumnName(), targetDao.getObjectId(propertyValue));
        }
      }
    }

    return cascadedProperties;
  }

  protected void persistOneToManyCollectionItems(PropertyConfig cascadePersistProperty, Dao targetDao, Map<String, Object> documentProperties, Collection propertyValue) throws CouchbaseLiteException, SQLException {
    List<String> persistedItemIds = new ArrayList<>();

    for(Object item : propertyValue) {
      targetDao.create(item);

      persistedItemIds.add(targetDao.getObjectId(item));
    }

    writeOneToManyJoinedEntityIdsToProperty(persistedItemIds, cascadePersistProperty, documentProperties);
  }

  protected void persistManyToManyCollectionItems(PropertyConfig cascadePersistProperty, Dao targetDao, Map<String, Object> cascadedProperties, Collection propertyValue) throws CouchbaseLiteException, SQLException {
    // TODO: currently we storing redundantly target entities' ids on both side, may implement JoinTables for ManyToMany properties
    persistOneToManyCollectionItems(cascadePersistProperty, targetDao, cascadedProperties, propertyValue);
  }


  public Collection<Object> retrieve(Collection<Object> ids) throws SQLException {
    Collection<Object> retrievedObjects = new ArrayList<>();

    for(Object id : ids) {
      retrievedObjects.add(retrieve(id));
    }

    return retrievedObjects;
  }

  public Object retrieve(Object id) throws SQLException {
    if(objectCache.containsObjectForId(entityClass, id)) {
      return objectCache.get(entityClass, id);
    }
    else {
      Document storedDocument = retrieveStoredDocumentForId(id);
      Object retrievedObject = createObjectFromDocument(storedDocument);

      entityConfig.invokePostLoadLifeCycleMethod(retrievedObject);

      return retrievedObject;
    }
  }

  protected Object createObjectFromDocument(Document document) throws SQLException {
    try {
      Object newInstance = entityConfig.getConstructor().newInstance();

      objectCache.add(entityClass, document.getId(), newInstance);

      setPropertiesOnObject(newInstance, document);

      return newInstance;
    } catch(Exception e) {
      throw new SQLException("Could not create Instance of " + entityConfig.getEntityClass().getSimpleName(), e);
    }
  }

  protected void setPropertiesOnObject(Object object, Document document) throws SQLException {
    setIdOnObject(object, document);
    updateVersionOnObject(object, document);

    for(PropertyConfig property : entityConfig.getProperties()) {
      if(isCouchbaseLiteSystemProperty(property) == false) {
        setPropertyOnObject(object, document, property); // TODO: catch Exception for setting single Property or let it bubble up and therefor stop Object creation?
      }
    }
  }

  protected void setPropertyOnObject(Object object, Document document, PropertyConfig property) throws SQLException {
    Object propertyValue = getValueFromDocument(document, property);

    if(propertyValue == null || property.isRelationshipProperty() == false) {
      setValueOnObject(object, property, propertyValue);
    }
    else {
      Dao targetDao = relationshipDaoCache.getTargetDaoForRelationshipProperty(property);

      if(property.isCollectionProperty() == false) {
        Object deserializedTargetInstance = targetDao.retrieve(propertyValue);
        setValueOnObject(object, property, deserializedTargetInstance);
      }
      else {
        setCollectionPropertyOnObject(object, property, targetDao, (String)propertyValue);
      }
    }
  }

  protected void setCollectionPropertyOnObject(Object object, PropertyConfig property, Dao targetDao, String joinedEntityIdsString) throws SQLException {
    Object propertyValue = getPropertyValue(object, property);

    if(propertyValue instanceof EntitiesCollection == false) {
      // TODO: what to do with joinedEntityIdsString in this case? As EntitiesCollection re-retrieves it, this is a waste of CPU time
      createAndSetEntitiesCollection(object, property); // EntitiesCollection will retrieve its items in Constructor
    }
    else {
      Collection<Object> itemIds = parseJoinedEntityIdsToList(joinedEntityIdsString, property);
      Collection<Object> retrievedItems = targetDao.retrieve(itemIds);

      Collection collection = (Collection)propertyValue;
      collection.clear();

      for(Object item : retrievedItems) {
        collection.add(item);
      }
    }
  }

  protected Object getValueFromDocument(Document document, PropertyConfig property) {
    Object value = document.getProperty(property.getColumnName());

    if(property.getType() == Date.class && value instanceof Long) {
      value = new Date((long)value);
    }

    return value;
  }


  public boolean update(Object object) throws SQLException, CouchbaseLiteException {
    checkIfCrudOperationCanBePerformedOnObjectOfClass(object, CrudOperation.UPDATE);

    Document storedDocument = retrieveStoredDocument(object);

    entityConfig.invokePreUpdateLifeCycleMethod(object);

    Map<String, Object> updatedProperties = mapProperties(object, entityConfig, storedDocument);

    storedDocument.putProperties(updatedProperties);

    updateVersionOnObject(object, storedDocument);

    // TODO: is there a kind of Cascade Update?

    entityConfig.invokePostUpdateLifeCycleMethod(object);

    return true;
  }


  protected Document retrieveStoredDocument(Object object) throws SQLException {
    String id = getObjectId(object);

    return retrieveStoredDocumentForId(id);
  }

  protected Document retrieveStoredDocumentForId(Object id) throws SQLException {
    Document storedDocument = database.getExistingDocument((String)id);

    if(storedDocument == null) {
      throw new SQLException("There's no existing Document with ID " + id);
    }

    return storedDocument;
  }


  public boolean delete(Object object) throws SQLException, CouchbaseLiteException {
    checkIfCrudOperationCanBePerformedOnObjectOfClass(object, CrudOperation.DELETE);

    Document storedDocument = retrieveStoredDocument(object);

    if(storedDocument.isDeleted() == false) {
      entityConfig.invokePreRemoveLifeCycleMethod(object);
      String id = storedDocument.getId();

      boolean result = storedDocument.delete();

      // TODO: should id be reset on Object?
      updateVersionOnObject(object, storedDocument); // TODO: after delete documents version is set to null -> really update object's version?

      objectCache.remove(entityClass, id);

      deleteCascadeRemoveProperties(object);

      entityConfig.invokePostRemoveLifeCycleMethod(object);

      return result;
    }

    return false;
  }

  protected void deleteCascadeRemoveProperties(Object object) throws SQLException, CouchbaseLiteException {
    for(PropertyConfig cascadeRemoveProperty : entityConfig.getRelationshipPropertiesWithCascadeRemove()) {
      Dao targetDao = relationshipDaoCache.getTargetDaoForRelationshipProperty(cascadeRemoveProperty);
      Object propertyValue = getPropertyValue(object, cascadeRemoveProperty);

      if(propertyValue != null) { // TODO: check if propertyValue's ID is set (if null means already deleted)?
        if(cascadeRemoveProperty.isCollectionProperty()) {
          for(Object item : (Collection)propertyValue) {
            if(targetDao.delete(item)) {
              // TODO: remove item from Collection then?
            }
          }
        }
        else {
          if (targetDao.delete(propertyValue)) {
            // TODO: set Property value to null then?
          }
        }
      }
    }
  }


  public Collection<Object> getJoinedItemsIds(Object object, PropertyConfig collectionProperty) throws SQLException {
    if(isAlreadyPersisted(object)) {
      Document objectDocument = retrieveStoredDocument(object);

      String itemIdsString = (String) objectDocument.getProperties().get(collectionProperty.getColumnName());

      if (itemIdsString != null) { // on initial EntitiesCollection creation itemIdsString is null
        return parseJoinedEntityIdsToList(itemIdsString, collectionProperty);
      }
    }

    return new HashSet<>();
  }

  protected Collection<Object> parseJoinedEntityIdsToList(String itemIdsString, PropertyConfig property) throws SQLException {
    try {
      // TODO: implement OrderBy

      ObjectMapper objectMapper = getObjectMapper();
      Collection<Object> itemIds = objectMapper.readValue(itemIdsString, Set.class);

      if(property.hasOrderColumns()) {
        itemIds = sortItems(itemIds, property);
      }

      return itemIds;
    } catch (Exception e) {
      throw new SQLException("Could not parse String " + itemIdsString + " to List with joined Entity Ids", e);
    }
  }

  /**
   * For Couchbase Lite we have to implement sorting Entities according @OrderBy Annotations in memory.
   * @param itemIds
   * @param property
   * @return
   */
  protected Collection<Object> sortItems(Collection<Object> itemIds, PropertyConfig property) throws SQLException {
    // there's not other way to sort ids then loading their Documents which may thwart Lazy Loading
    final Map<Object, Document> mapIdToDocument = getDocumentsToIds(itemIds);
    Set<Object> sortedSet = new HashSet(itemIds);

    for(int i = property.getOrderColumns().size() - 1; i >= 0; i--) {
      final OrderByConfig orderBy = property.getOrderColumns().get(i);

      TreeSet<Object> sortedTempSet = new TreeSet<>(new Comparator<Object>() {
        @Override
        public int compare(Object id1, Object id2) {
          return compareObjects(mapIdToDocument, id1, id2, orderBy);
        }
      });

      sortedTempSet.addAll(sortedSet);

      sortedSet = sortedTempSet;
    }

    return sortedSet;
  }

  protected Map<Object, Document> getDocumentsToIds(Collection<Object> itemIds) throws SQLException {
    Map<Object, Document> mapIdToDocument = new HashMap<>();

    for(Object id : itemIds) {
      mapIdToDocument.put(id, retrieveStoredDocumentForId(id));
    }

    return mapIdToDocument;
  }

  protected int compareObjects(Map<Object, Document> mapIdToDocument, Object id1, Object id2, OrderByConfig orderBy) {
    Object object1Value = null, object2Value = null;
    try { object1Value = getValueFromDocument(mapIdToDocument.get(id1), orderBy.getOrderByTargetProperty()); } catch(Exception ignored) { }
    try { object2Value = getValueFromDocument(mapIdToDocument.get(id2), orderBy.getOrderByTargetProperty()); } catch(Exception ignored) { }

    if(object1Value == null && object2Value == null) {
      return 0;
    }
    else if(object1Value == null) {
      return -1;
    }
    else if(object2Value == null) {
      return 1;
    }

    int compareValue = 0;

    if(object1Value instanceof Comparable && object2Value instanceof Comparable) {
      compareValue = ((Comparable)object1Value).compareTo(object2Value);

      if(orderBy.isAscending() == false) {
        compareValue = compareValue * (-1); // invert ordering
      }
    }

    return compareValue;
  }


  protected boolean isAlreadyPersisted(Object object) throws SQLException {
    return getObjectId(object) != null;
  }

  public String getObjectId(Object object) throws SQLException {
    return (String)getPropertyValue(object, entityConfig.getIdProperty());
  }

  protected void setIdOnObject(Object object, Document newDocument) throws SQLException {
    setValueOnObject(object, entityConfig.getIdProperty(), newDocument.getId());
  }

  protected void updateVersionOnObject(Object object, Document newDocument) throws SQLException {
    if(entityConfig.isVersionPropertySet()) {
      setValueOnObject(object, entityConfig.getVersionProperty(), newDocument.getCurrentRevisionId());
    }
  }


  protected void checkIfCrudOperationCanBePerformedOnObjectOfClass(Object object, CrudOperation crudOperation) throws SQLException {
    if(object == null) {
      throw new SQLException("Object to " + crudOperation + " may not be null");
    }
    if(entityConfig.getEntityClass().isAssignableFrom(object.getClass()) == false) {
      throw new SQLException("Object to " + crudOperation + " of class " + object.getClass() + " is not of Dao's Entity class " + entityConfig.getEntityClass());
    }

    if(crudOperation == CrudOperation.CREATE && isAlreadyPersisted(object) == true) {
      throw new SQLException("Trying to Persist Object " + object + " but is already persisted.");
    }
    else if(crudOperation != CrudOperation.CREATE && isAlreadyPersisted(object) == false) {
      throw new SQLException("Object " + object + " is not persisted yet, cannot perform " + crudOperation + ".");
    }
  }


  protected Map<String, Object> mapProperties(Object object, EntityConfig entityConfig, Document storedDocument) throws SQLException {
    Map<String, Object> mappedProperties = new HashMap<>();

    boolean isInitialPersist = storedDocument == null;
    if(storedDocument != null) {
      mappedProperties.putAll(storedDocument.getProperties());
    }

    for(PropertyConfig property : entityConfig.getProperties()) {
      if(shouldPropertyBeAdded(isInitialPersist, property)) {
        mapProperty(object, mappedProperties, property, isInitialPersist);
      }
    }

    return mappedProperties;
  }

  protected void mapProperty(Object object, Map<String, Object> mappedProperties, PropertyConfig property, boolean isInitialPersist) throws SQLException {
    Object propertyValue = getPropertyValue(object, property);

    if(property.isRelationshipProperty() == false) {
      mappedProperties.put(property.getColumnName(), propertyValue);
    }
    else { // for Objects persist only its ID respectively their IDs for Collections
      if(propertyValue == null) {
        mappedProperties.put(property.getColumnName(), null);
      }
      else if(relationshipDaoCache.containsTargetDaoForRelationshipProperty(property)) { // on correctly configured Entities should actually never be false
        Dao targetDao = relationshipDaoCache.getTargetDaoForRelationshipProperty(property);
        if(property.isCollectionProperty() == false) {
          mappedProperties.put(property.getColumnName(), targetDao.getObjectId(propertyValue));
        }
        else {
          mapCollectionProperty(object, property, mappedProperties, targetDao, (Collection)propertyValue, isInitialPersist);
        }
      }
    }
  }

  protected void mapCollectionProperty(Object object, PropertyConfig collectionProperty, Map<String, Object> mappedProperties, Dao targetDao, Collection propertyValue, boolean isInitialPersist) throws SQLException {
    if(propertyValue instanceof EntitiesCollection == false) {
      if(isInitialPersist) {
        return; // EntitiesCollection will then be created in createCascadePersistProperties()
      }
      else {
        createAndSetEntitiesCollectionAndAddExistingItems(object, collectionProperty, propertyValue);
      }
    }

    List joinedEntityIds = new ArrayList();

    for(Object item : propertyValue) {
      Object itemId = targetDao.getObjectId(item);
      if(itemId != null) { // TODO: what if item is not persisted yet? // should actually never be the case as not persisted entities may not get added to EntitiesCollection
        joinedEntityIds.add(itemId);
      }
    }

    writeOneToManyJoinedEntityIdsToProperty(joinedEntityIds, collectionProperty, mappedProperties);
  }

  protected Collection createEntitiesCollection(Object object, PropertyConfig collectionProperty) throws SQLException {
    Dao targetDao = relationshipDaoCache.getTargetDaoForRelationshipProperty(collectionProperty);
    Collection collection = null;

    if(collectionProperty.isManyToManyField() == false) {
      if(collectionProperty.isLazyLoading()) {
        collection = new LazyLoadingEntitiesCollection(object, collectionProperty, this, targetDao);
      }
      else {
        collection = new EntitiesCollection(object, collectionProperty, this, targetDao);
      }
    }
    else {
      if(collectionProperty.isLazyLoading()) {
        // TODO
      }
      else {
        collection = new ManyToManyEntitiesCollection(object, collectionProperty, this, targetDao); // TODO: also pass JoinTable Dao
      }
    }

    return collection;
  }

  protected void createAndSetEntitiesCollection(Object object, PropertyConfig collectionProperty) throws SQLException {
    Collection collection = createEntitiesCollection(object, collectionProperty);

    setValueOnObject(object, collectionProperty, collection);
  }

  protected void createAndSetEntitiesCollectionAndAddExistingItems(Object object, PropertyConfig collectionProperty, Object propertyValue) throws SQLException {
    Collection collection = createEntitiesCollection(object, collectionProperty);

    for(Object currentItem : (Collection)propertyValue) {
      collection.add(currentItem);
    }

    setValueOnObject(object, collectionProperty, collection);
  }

  protected void writeOneToManyJoinedEntityIdsToProperty(List joinedEntityIds, PropertyConfig property, Map<String, Object> documentProperties) throws SQLException {
    try {
      ObjectMapper objectMapper = getObjectMapper();
      String itemIdsString = objectMapper.writeValueAsString(joinedEntityIds);
      documentProperties.put(property.getColumnName(), itemIdsString);
    } catch (JsonProcessingException e) {
      throw new SQLException("Could not persist IDs of Collection Items", e);
    }
  }


  protected boolean shouldPropertyBeAdded(boolean isInitialPersist, PropertyConfig property) {
    return ! ( isInitialPersist && isCouchbaseLiteSystemProperty(property) );
  }

  protected boolean isCouchbaseLiteSystemProperty(PropertyConfig property) {
    return property.isId() || property.isVersion();
  }

  protected Object getPropertyValue(Object object, PropertyConfig property) throws SQLException {
    Object value;

    if(shouldUseGetter(property)) {
      try {
        value = property.getFieldGetMethod().invoke(object);
      } catch (Exception e) {
        throw new SQLException("Could not call " + property.getFieldGetMethod() + " for " + property, e);
      }
    }
    else {
      try {
        value = property.getField().get(object);
      } catch (Exception e) {
        throw new SQLException("Could not get field value for " + property, e);
      }
    }

    return value;
  }


  protected void setValueOnObject(Object object, PropertyConfig property, Object value) throws SQLException {
    if(shouldUseSetter(property)) {
      try {
        property.getFieldSetMethod().invoke(object, value);
      } catch (Exception e) {
        throw new SQLException("Could not call " + property.getFieldSetMethod() + " for " + property, e);
      }
    }
    else {
      try {
        // field object may not be a T yet
        property.getField().set(object, value);
      } catch (Exception e) {
        throw new SQLException("Could not set field value for " + property, e);
      }
    }
  }

  protected boolean shouldUseGetter(PropertyConfig property) {
    return (entityConfig.getAccess() == AccessType.PROPERTY && property.getFieldGetMethod() != null) ||
        (property.getField() == null && property.getFieldGetMethod() != null);
  }

  protected boolean shouldUseSetter(PropertyConfig property) {
    return (entityConfig.getAccess() == AccessType.PROPERTY && property.getFieldSetMethod() != null) ||
        (property.getField() == null && property.getFieldSetMethod() != null);
  }


  protected ObjectMapper getObjectMapper() {
    if(objectMapper == null) {
      objectMapper = new ObjectMapper();
    }

    return objectMapper;
  }
}
