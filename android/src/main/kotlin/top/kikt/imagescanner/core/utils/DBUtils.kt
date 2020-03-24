package top.kikt.imagescanner.core.utils

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import top.kikt.imagescanner.core.PhotoManager
import top.kikt.imagescanner.core.cache.CacheContainer
import top.kikt.imagescanner.core.entity.AssetEntity
import top.kikt.imagescanner.core.entity.FilterOption
import top.kikt.imagescanner.core.entity.GalleryEntity
import top.kikt.imagescanner.core.utils.IDBUtils.Companion.storeBucketKeys
import top.kikt.imagescanner.core.utils.IDBUtils.Companion.storeImageKeys
import top.kikt.imagescanner.core.utils.IDBUtils.Companion.storeVideoKeys
import top.kikt.imagescanner.core.utils.IDBUtils.Companion.typeKeys
import java.io.File
import java.io.InputStream
import java.net.URLConnection


/// create 2019-09-05 by cai
/// Call the MediaStore API and get entity for the data.
@Suppress("DEPRECATION")
object DBUtils : IDBUtils {
  
  private const val TAG = "DBUtils"
  
  private val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  private val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
  
  private val cacheContainer = CacheContainer()
  
  private val locationKeys = arrayOf(
    MediaStore.Images.ImageColumns.LONGITUDE,
    MediaStore.Images.ImageColumns.LATITUDE
  )
  
  private fun convertTypeToUri(type: Int): Uri {
    return when (type) {
      1 -> imageUri
      2 -> videoUri
      else -> allUri
    }
  }
  
  @SuppressLint("Recycle")
  override fun getGalleryList(context: Context, requestType: Int, timeStamp: Long, option: FilterOption): List<GalleryEntity> {
    val list = ArrayList<GalleryEntity>()
    val uri = allUri
    val projection = storeBucketKeys + arrayOf("count(1)")
    
    val args = ArrayList<String>()
    val typeSelection: String = getCondFromType(requestType, option, args)
    
    val dateSelection = getDateCond(args, timeStamp, option)
    
    val sizeWhere = sizeWhere(requestType, option)
    
    val selection = "${MediaStore.Images.Media.BUCKET_ID} IS NOT NULL $typeSelection $dateSelection $sizeWhere) GROUP BY (${MediaStore.Images.Media.BUCKET_ID}"
    val cursor = context.contentResolver.query(uri, projection, selection, args.toTypedArray(), null)
      ?: return emptyList()
    while (cursor.moveToNext()) {
      val id = cursor.getString(0)
      val name = cursor.getString(1) ?: ""
      val count = cursor.getInt(2)
      list.add(GalleryEntity(id, name, count, 0))
    }
    
    cursor.close()
    return list
  }
  
  override fun getOnlyGalleryList(context: Context, requestType: Int, timeStamp: Long, option: FilterOption): List<GalleryEntity> {
    val list = ArrayList<GalleryEntity>()
    
    val args = ArrayList<String>()
    val typeSelection: String = AndroidQDBUtils.getCondFromType(requestType, option, args)
    val projection = storeBucketKeys + arrayOf("count(1)")
    
    val dateSelection = getDateCond(args, timeStamp, option)
    
    val sizeWhere = sizeWhere(requestType, option)
    
    val selections = "${MediaStore.Images.Media.BUCKET_ID} IS NOT NULL $typeSelection $dateSelection $sizeWhere"
    
    val cursor = context.contentResolver.query(allUri, projection, selections, args.toTypedArray(), null)
      ?: return list
    
    cursor.use {
      val count = cursor.count
      val galleryEntity = GalleryEntity(PhotoManager.ALL_ID, "Recent", count, requestType, true)
      list.add(galleryEntity)
    }
    
    return list
  }
  
  override fun getGalleryEntity(context: Context, galleryId: String, type: Int, timeStamp: Long, option: FilterOption): GalleryEntity? {
    val uri = allUri
    val projection = storeBucketKeys + arrayOf("count(1)")
    
    val args = ArrayList<String>()
    val typeSelection: String = getCondFromType(type, option, args)
    
    val dateSelection = getDateCond(args, timeStamp, option)
    
    val idSelection: String
    if (galleryId == "") {
      idSelection = ""
    } else {
      idSelection = "AND ${MediaStore.Images.Media.BUCKET_ID} = ?"
      args.add(galleryId)
    }
    
    val sizeWhere = sizeWhere(null, option)
    
    val selection = "${MediaStore.Images.Media.BUCKET_ID} IS NOT NULL $typeSelection $dateSelection $idSelection $sizeWhere) GROUP BY (${MediaStore.Images.Media.BUCKET_ID}"
    val cursor = context.contentResolver.query(uri, projection, selection, args.toTypedArray(), null)
      ?: return null
    return if (cursor.moveToNext()) {
      val id = cursor.getString(0)
      val name = cursor.getString(1) ?: ""
      val count = cursor.getInt(2)
      cursor.close()
      GalleryEntity(id, name, count, 0)
    } else {
      cursor.close()
      null
    }
  }
  
  override fun getThumbUri(context: Context, id: String, width: Int, height: Int, type: Int?): Uri? {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
  
  @SuppressLint("Recycle")
  override fun getAssetFromGalleryId(
    context: Context,
    galleryId: String,
    page: Int,
    pageSize: Int,
    requestType: Int,
    timeStamp: Long,
    option: FilterOption,
    cacheContainer: CacheContainer?
  ): List<AssetEntity> {
    val cache = cacheContainer ?: this.cacheContainer
    
    val isAll = galleryId.isEmpty()
    
    val list = ArrayList<AssetEntity>()
    val uri = allUri
    
    val args = ArrayList<String>()
    if (!isAll) {
      args.add(galleryId)
    }
    val typeSelection = getCondFromType(requestType, option, args)
    
    val dateSelection = getDateCond(args, timeStamp, option)
    
    val sizeWhere = sizeWhere(requestType, option)
    
    val keys = (storeImageKeys + storeVideoKeys + typeKeys + locationKeys).distinct().toTypedArray()
    val selection = if (isAll) {
      "${MediaStore.Images.ImageColumns.BUCKET_ID} IS NOT NULL $typeSelection $dateSelection $sizeWhere"
    } else {
      "${MediaStore.Images.ImageColumns.BUCKET_ID} = ? $typeSelection $dateSelection $sizeWhere"
    }
    
    val sortOrder = getSortOrder(page * pageSize, pageSize, option)
    val cursor = context.contentResolver.query(uri, keys, selection, args.toTypedArray(), sortOrder)
      ?: return emptyList()
    
    while (cursor.moveToNext()) {
      val asset = convertCursorToAsset(cursor, requestType)
      list.add(asset)
      cache.putAsset(asset)
    }
    
    cursor.close()
    
    return list
  }
  
  override fun getAssetFromGalleryIdRange(context: Context, gId: String, start: Int, end: Int, requestType: Int, timestamp: Long, option: FilterOption): List<AssetEntity> {
    val cache = cacheContainer
    
    val isAll = gId.isEmpty()
    
    val list = ArrayList<AssetEntity>()
    val uri = allUri
    
    val args = ArrayList<String>()
    if (!isAll) {
      args.add(gId)
    }
    val typeSelection = getCondFromType(requestType, option, args)
    
    val dateSelection = getDateCond(args, timestamp, option)
    
    val sizeWhere = sizeWhere(requestType, option)
    
    val keys = (storeImageKeys + storeVideoKeys + typeKeys + locationKeys).distinct().toTypedArray()
    val selection = if (isAll) {
      "${MediaStore.Images.ImageColumns.BUCKET_ID} IS NOT NULL $typeSelection $dateSelection $sizeWhere"
    } else {
      "${MediaStore.Images.ImageColumns.BUCKET_ID} = ? $typeSelection $dateSelection $sizeWhere"
    }
    
    val pageSize = end - start
    
    val sortOrder = getSortOrder(start, pageSize, option)
    val cursor = context.contentResolver.query(uri, keys, selection, args.toTypedArray(), sortOrder)
      ?: return emptyList()
    
    while (cursor.moveToNext()) {
      val asset = convertCursorToAsset(cursor, requestType)
      
      list.add(asset)
      cache.putAsset(asset)
    }
    
    cursor.close()
    
    return list
  }
  
  private fun convertCursorToAsset(cursor: Cursor, requestType: Int): AssetEntity {
    val id = cursor.getString(MediaStore.MediaColumns._ID)
    val path = cursor.getString(MediaStore.MediaColumns.DATA)
    val date = cursor.getLong(MediaStore.Images.Media.DATE_TAKEN)
    val type = cursor.getInt(MediaStore.Files.FileColumns.MEDIA_TYPE)
    val duration = if (requestType == 1) 0 else cursor.getLong(MediaStore.Video.VideoColumns.DURATION)
    val width = cursor.getInt(MediaStore.MediaColumns.WIDTH)
    val height = cursor.getInt(MediaStore.MediaColumns.HEIGHT)
    val displayName = File(path).name
    val modifiedDate = cursor.getLong(MediaStore.MediaColumns.DATE_MODIFIED)
    
    val lat = cursor.getDouble(MediaStore.Images.ImageColumns.LATITUDE)
    val lng = cursor.getDouble(MediaStore.Images.ImageColumns.LONGITUDE)
    val orientation: Int = cursor.getInt(MediaStore.MediaColumns.ORIENTATION)
    
    return AssetEntity(id, path, duration, date, width, height, getMediaType(type), displayName, modifiedDate, orientation, lat, lng)
  }
  
  @SuppressLint("Recycle")
  override fun getAssetEntity(context: Context, id: String): AssetEntity? {
    val asset = cacheContainer.getAsset(id)
    if (asset != null) {
      return asset
    }
    
    val keys = (storeImageKeys + storeVideoKeys + locationKeys + typeKeys).distinct().toTypedArray()
    
    val selection = "${MediaStore.Files.FileColumns._ID} = ?"
    
    val args = arrayOf(id)
    
    val cursor = context.contentResolver.query(allUri, keys, selection, args, null)
      ?: return null
    
    if (cursor.moveToNext()) {
      val type = cursor.getInt(MediaStore.Files.FileColumns.MEDIA_TYPE)
      val dbAsset = convertCursorToAsset(cursor, type)
      
      cacheContainer.putAsset(dbAsset)
      
      cursor.close()
      return dbAsset
    } else {
      cursor.close()
      return null
    }
  }
  
  override fun getOriginBytes(context: Context, asset: AssetEntity, haveLocationPermission: Boolean): ByteArray {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
  
  override fun cacheOriginFile(context: Context, asset: AssetEntity, byteArray: ByteArray) {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
  
  override fun getExif(context: Context, id: String): ExifInterface? {
    val asset = getAssetEntity(context, id) ?: return null
    return ExifInterface(asset.path)
  }
  
  override fun getFilePath(context: Context, id: String, origin: Boolean): String? {
    val assetEntity = getAssetEntity(context, id) ?: return null
    return assetEntity.path
  }
  
  override fun clearCache() {
    cacheContainer.clearCache()
  }
  
  override fun saveImage(context: Context, image: ByteArray, title: String, desc: String): AssetEntity? {
    val bmp = BitmapFactory.decodeByteArray(image, 0, image.count())
    val insertImage = MediaStore.Images.Media.insertImage(context.contentResolver, bmp, title, desc)
    val id = ContentUris.parseId(Uri.parse(insertImage))
    return getAssetEntity(context, id.toString())
  }
  
  override fun saveVideo(context: Context, inputStream: InputStream, title: String, desc: String): AssetEntity? {
    val cr = context.contentResolver
    val timestamp = System.currentTimeMillis() / 1000
    
    var typeFromStream: String? = URLConnection.guessContentTypeFromStream(inputStream)
    
    if (typeFromStream == null) {
      typeFromStream = "video/${File(title).extension}"
    }
    
    val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, title)
      put(MediaStore.Video.VideoColumns.MIME_TYPE, typeFromStream)
      put(MediaStore.Video.VideoColumns.TITLE, title)
      put(MediaStore.Video.VideoColumns.DESCRIPTION, desc)
      put(MediaStore.Video.VideoColumns.DATE_ADDED, timestamp)
      put(MediaStore.Video.VideoColumns.DATE_MODIFIED, timestamp)
    }
    
    val contentUri = cr.insert(uri, values) ?: return null
    val outputStream = cr.openOutputStream(contentUri)
    
    outputStream?.use {
      inputStream.use {
        inputStream.copyTo(outputStream)
      }
    }
    
    val id = ContentUris.parseId(contentUri)
    
    cr.notifyChange(contentUri, null)
    return getAssetEntity(context, id.toString())
  }
  
  override fun getMediaUri(context: Context, id: String, type: Int): String {
    val asset = getAssetEntity(context, id) ?: return ""
    return File(asset.path).toURI().toString()
  }
  
}