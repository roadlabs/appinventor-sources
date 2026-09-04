// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2025 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.local;

import com.google.appinventor.client.utils.Promise;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.typedarrays.shared.ArrayBuffer;

/**
 * A thin wrapper around IndexedDB, which serves as the sole persistent store
 * for the offline App Inventor webapp.
 *
 * <p>The database {@code ai2-offline} (version 1) contains two object stores:
 * <ul>
 *   <li>{@code contents} - records {@code {key, value}} where key is
 *       {@code "<projectId>:<fileId>"} and value is the file's bytes. Project
 *       file contents, including uploaded media, are stored one record per
 *       file so that large binaries are written and deleted individually.</li>
 *   <li>{@code meta} - records {@code {key, value}} with string values. The
 *       single {@link #PROJECTS_KEY} record holds the project list as a JSON
 *       array in the same format the legacy localStorage persistence used.</li>
 * </ul>
 *
 * <p>All methods return {@link Promise}s that reject when IndexedDB is
 * unavailable (e.g. private browsing in older browsers).
 */
public final class LocalIdbStore {
  /** The key of the projects-list record in the meta store. */
  public static final String PROJECTS_KEY = "projects";

  /** Prefix for user-file records stored in the contents object store. */
  private static final String USER_FILE_PREFIX = "__userfile__:";

  private static Promise<Void> openPromise;
  private static JavaScriptObject dbHandle;

  private LocalIdbStore() {
  }

  /**
   * Opens (creating if necessary) the {@code ai2-offline} database. Safe to
   * call repeatedly; the database handle is memoized.
   *
   * @return a promise that resolves once the database is open, or rejects if
   *         IndexedDB is unavailable
   */
  public static Promise<Void> open() {
    if (openPromise == null) {
      openPromise = openRaw();
    }
    return openPromise;
  }

  private static native Promise<Void> openRaw() /*-{
    return new Promise(function(resolve, reject) {
      if (!$wnd.indexedDB) {
        reject(new Error('IndexedDB is unavailable'));
        return;
      }
      var req = $wnd.indexedDB.open('ai2-offline', 1);
      req.onupgradeneeded = function(ev) {
        var db = ev.target.result;
        if (!db.objectStoreNames.contains('contents')) {
          db.createObjectStore('contents', { keyPath: 'key' });
        }
        if (!db.objectStoreNames.contains('meta')) {
          db.createObjectStore('meta', { keyPath: 'key' });
        }
      };
      req.onsuccess = function(ev) {
        @com.google.appinventor.client.local.LocalIdbStore::dbHandle = ev.target.result;
        resolve(null);
      };
      req.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to open IndexedDB'));
      };
      req.onblocked = function(ev) {
        reject(new Error('Opening IndexedDB was blocked by another tab'));
      };
    });
  }-*/;

  /**
   * Reads every record in the contents store.
   *
   * @return a promise resolving to a plain JavaScript object mapping content
   *         keys to ArrayBuffers; walk it with {@link #keysToArray} and
   *         {@link #mapGet}
   */
  public static Promise<JavaScriptObject> getAllContents() {
    return open().then0(() -> getAllContentsRaw());
  }

  private static native Promise<JavaScriptObject> getAllContentsRaw() /*-{
    var db = @com.google.appinventor.client.local.LocalIdbStore::dbHandle;
    return new Promise(function(resolve, reject) {
      var result = {};
      var tx = db.transaction('contents', 'readonly');
      var req = tx.objectStore('contents').getAll();
      req.onsuccess = function(ev) {
        var rows = ev.target.result || [];
        for (var i = 0; i < rows.length; i++) {
          result[rows[i].key] = rows[i].value;
        }
      };
      tx.oncomplete = function() { resolve(result); };
      tx.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to read from IndexedDB'));
      };
      tx.onabort = function(ev) {
        reject(ev.target.error || new Error('Reading from IndexedDB was aborted'));
      };
    });
  }-*/;

  /**
   * Writes (or overwrites) a single content record.
   *
   * @param key the content key ({@code "<projectId>:<fileId>"})
   * @param value the file's bytes
   * @return a promise that resolves once the write is durable
   */
  public static Promise<Void> putContent(String key, ArrayBuffer value) {
    return open().then0(() -> putContentRaw(key, value));
  }

  private static native Promise<Void> putContentRaw(String key, ArrayBuffer value) /*-{
    var db = @com.google.appinventor.client.local.LocalIdbStore::dbHandle;
    return new Promise(function(resolve, reject) {
      var tx = db.transaction('contents', 'readwrite');
      tx.objectStore('contents').put({ key: key, value: value });
      tx.oncomplete = function() { resolve(null); };
      tx.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to write to IndexedDB'));
      };
      tx.onabort = function(ev) {
        reject(ev.target.error || new Error('Writing to IndexedDB was aborted'));
      };
    });
  }-*/;

  /**
   * Deletes a single content record.
   *
   * @param key the content key ({@code "<projectId>:<fileId>"})
   * @return a promise that resolves once the delete is durable
   */
  public static Promise<Void> deleteContent(String key) {
    return open().then0(() -> deleteContentRaw(key));
  }

  private static native Promise<Void> deleteContentRaw(String key) /*-{
    var db = @com.google.appinventor.client.local.LocalIdbStore::dbHandle;
    return new Promise(function(resolve, reject) {
      var tx = db.transaction('contents', 'readwrite');
      var req = tx.objectStore('contents')['delete'](key);
      tx.oncomplete = function() { resolve(null); };
      tx.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to delete from IndexedDB'));
      };
      tx.onabort = function(ev) {
        reject(ev.target.error || new Error('Deleting from IndexedDB was aborted'));
      };
    });
  }-*/;

  /** Stores a user file in the same binary contents store as project files. */
  public static Promise<Void> putUserFile(String fileName, ArrayBuffer value) {
    return putContent(USER_FILE_PREFIX + fileName, value);
  }

  /** Returns whether a user file is present. */
  public static Promise<Boolean> hasUserFile(String fileName) {
    return open().then0(() -> hasContentRaw(USER_FILE_PREFIX + fileName));
  }

  /** Reads a user file, or resolves to null when it is absent. */
  public static Promise<ArrayBuffer> getUserFile(String fileName) {
    return open().then0(() -> getContentRaw(USER_FILE_PREFIX + fileName));
  }

  /** Deletes a user file. */
  public static Promise<Void> deleteUserFile(String fileName) {
    return deleteContent(USER_FILE_PREFIX + fileName);
  }

  private static native Promise<Boolean> hasContentRaw(String key) /*-{
    var db = @com.google.appinventor.client.local.LocalIdbStore::dbHandle;
    return new Promise(function(resolve, reject) {
      var req = db.transaction('contents', 'readonly').objectStore('contents').get(key);
      req.onsuccess = function() { resolve(!!req.result); };
      req.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to inspect IndexedDB'));
      };
    });
  }-*/;

  private static native Promise<ArrayBuffer> getContentRaw(String key) /*-{
    var db = @com.google.appinventor.client.local.LocalIdbStore::dbHandle;
    return new Promise(function(resolve, reject) {
      var req = db.transaction('contents', 'readonly').objectStore('contents').get(key);
      req.onsuccess = function() { resolve(req.result ? req.result.value : null); };
      req.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to read from IndexedDB'));
      };
    });
  }-*/;

  /**
   * Reads a meta record's string value.
   *
   * @param key the meta record key (e.g. {@link #PROJECTS_KEY})
   * @return a promise resolving to the record's value, or to {@code null}
   *         when no such record exists
   */
  public static Promise<String> getMeta(String key) {
    return open().then0(() -> getMetaRaw(key));
  }

  private static native Promise<String> getMetaRaw(String key) /*-{
    var db = @com.google.appinventor.client.local.LocalIdbStore::dbHandle;
    return new Promise(function(resolve, reject) {
      var req = db.transaction('meta', 'readonly').objectStore('meta').get(key);
      req.onsuccess = function(ev) {
        resolve(req.result ? req.result.value : null);
      };
      req.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to read from IndexedDB'));
      };
    });
  }-*/;

  /**
   * Writes (or overwrites) a meta record.
   *
   * @param key the meta record key
   * @param value the record's string value
   * @return a promise that resolves once the write is durable
   */
  public static Promise<Void> putMeta(String key, String value) {
    return open().then0(() -> putMetaRaw(key, value));
  }

  private static native Promise<Void> putMetaRaw(String key, String value) /*-{
    var db = @com.google.appinventor.client.local.LocalIdbStore::dbHandle;
    return new Promise(function(resolve, reject) {
      var tx = db.transaction('meta', 'readwrite');
      tx.objectStore('meta').put({ key: key, value: value });
      tx.oncomplete = function() { resolve(null); };
      tx.onerror = function(ev) {
        reject(ev.target.error || new Error('Failed to write to IndexedDB'));
      };
      tx.onabort = function(ev) {
        reject(ev.target.error || new Error('Writing to IndexedDB was aborted'));
      };
    });
  }-*/;

  /**
   * Returns the keys of a map produced by {@link #getAllContents()}.
   */
  static native JsArrayString keysToArray(JavaScriptObject map) /*-{
    var keys = [];
    for (var k in map) {
      keys.push(k);
    }
    return keys;
  }-*/;

  /**
   * Looks up a value in a map produced by {@link #getAllContents()}.
   */
  static native ArrayBuffer mapGet(JavaScriptObject map, String key) /*-{
    return map[key];
  }-*/;

  /**
   * Reads the legacy localStorage blob written by earlier offline builds.
   */
  public static native String loadLegacyLocalStorage() /*-{
    try { return $wnd.localStorage.getItem('ai2-offline-state'); }
    catch (e) { return null; }
  }-*/;

  /**
   * Removes the legacy localStorage blob after a successful migration.
   */
  public static native void clearLegacyLocalStorage() /*-{
    try { $wnd.localStorage.removeItem('ai2-offline-state'); }
    catch (e) { }
  }-*/;
}
