package net.dankito.jpa.relationship.collections;

import net.dankito.jpa.apt.config.ColumnConfig;
import net.dankito.jpa.couchbaselite.Dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class LazyLoadingEntitiesCollection extends EntitiesCollection {

  private static final Logger log = LoggerFactory.getLogger(LazyLoadingEntitiesCollection.class);


  protected Map<Object, Object> cachedEntities;

  protected boolean cacheEntities = true;


  public LazyLoadingEntitiesCollection(Object object, ColumnConfig property, Dao holdingObjectDao, Dao targetDao, Collection<Object> targetEntitiesIds) throws SQLException {
    super(object, property, holdingObjectDao, targetDao, targetEntitiesIds);

  }


  @Override
  protected void retrievedTargetEntities(Collection<Object> targetEntitiesIds) throws SQLException {
    if(cachedEntities == null) { // on collection initializing
      this.cachedEntities = new ConcurrentHashMap<>();
    }
    else {
      this.targetEntitiesIds.clear();
      this.targetEntitiesIds.addAll(targetEntitiesIds);
    }
  }

  @Override
  public Object get(int index) {
    Object id = targetEntitiesIds.get(index); // range check is implicitly done here

    Object cachedEntity = cachedEntities.get(id);
    if(cachedEntity != null) {
      return cachedEntity;
    }

    Object retrievedEntity = retrieveAndCacheEntity(id);

    return retrievedEntity;
  }

  protected Object retrieveAndCacheEntity(Object id) {
    try {
      Object retrievedEntity = targetDao.retrieve(id);

      cacheEntity(id, retrievedEntity);

      return retrievedEntity;
    } catch(Exception e) {
      log.error("Cannot retrieve Target Entity of Type " + property.getTargetEntity().getEntityClass() + " with id " + id, e);
    }

    return null;
  }


  @Override
  protected void itemAddedToCollection(int index, Object element, Object id) {
    cacheEntity(id, element);
  }

  @Override
  protected void itemRemovedFromCollection(Object object, Object id) {
    removeEntityFromCache(id, object);
  }


  protected void cacheEntity(Object id, Object entity) {
    if(cacheEntities) {
      cachedEntities.put(id, entity);
    }
  }

  protected boolean removeEntityFromCache(Object id, Object object) {
    return cachedEntities.remove(id) != null;
  }


  @Override
  public int size() {
    return targetEntitiesIds.size();
  }

  @Override
  public void clear() {
    targetEntitiesIds.clear();
    cachedEntities.clear();
  }

}
