package net.dankito.jpa.util;

import net.dankito.jpa.couchbaselite.Dao;

/**
 * Created by ganymed on 24/08/16.
 */
public class DatabaseSettings {

  public String sanitizeTableName(String extractedTableName) {
    return extractedTableName; // nothing to do here
  }

  public String sanitizeColumnName(String extractedColumnName) {
    if(isCouchbaseLiteSystemColumnName(extractedColumnName)) {
      return "_" + extractedColumnName;
    }

    return extractedColumnName;
  }

  protected boolean isCouchbaseLiteSystemColumnName(String extractedColumnName) {
    switch(extractedColumnName) {
      case "_id":
      case "_revision":
      case "_type": // yeah, actually not a Couchbase default 'Column', but i use it for Storing Entity Name // TODO: create a Constant for it
      case Dao.PARENT_DOCUMENT_ID_COLUMN_NAME:
        return true;
      default:
        return false;
    }
  }
}