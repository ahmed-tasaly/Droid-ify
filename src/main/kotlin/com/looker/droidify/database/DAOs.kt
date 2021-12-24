package com.looker.droidify.database

import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.looker.droidify.*
import com.looker.droidify.entity.ProductItem


interface BaseDao<T> {
    @Insert
    fun insert(vararg product: T)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(vararg obj: T): Int

    @Delete
    fun delete(obj: T)
}

@Dao
interface RepositoryDao : BaseDao<Repository> {
    @get:Query("SELECT COUNT(_id) FROM repository")
    val count: Int

    fun put(repository: com.looker.droidify.entity.Repository): com.looker.droidify.entity.Repository {
        repository.let {
            val dbRepo = Repository().apply {
                if (it.id >= 0L) id = it.id
                enabled = if (it.enabled) 1 else 0
                deleted = false
                data = it
            }
            val newId = if (it.id > 0L) update(dbRepo).toLong() else returnInsert(dbRepo)
            return if (newId != repository.id) repository.copy(id = newId) else repository
        }
    }

    @Insert
    fun returnInsert(product: Repository): Long

    @Query("SELECT * FROM repository WHERE _id = :id and deleted == 0")
    fun get(id: Long): Repository?

    @get:Query("SELECT * FROM repository WHERE deleted == 0 ORDER BY _id ASC")
    val allCursor: Cursor

    @get:Query("SELECT * FROM repository WHERE deleted == 0 ORDER BY _id ASC")
    val all: List<Repository>

    @get:Query("SELECT _id, deleted FROM repository WHERE deleted != 0 and enabled == 0 ORDER BY _id ASC")
    val allDisabledDeleted: List<Repository.IdAndDeleted>

    @Query("DELETE FROM repository WHERE _id = :id")
    fun deleteById(vararg id: Long): Int

    // TODO optimize
    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun markAsDeleted(id: Long) {
        get(id).apply { this?.deleted = true }?.let { update(it) }
    }
}

@Dao
interface ProductDao : BaseDao<Product> {
    @Query("SELECT COUNT(*) FROM product WHERE repository_id = :id")
    fun countForRepository(id: Long): Long

    @Query("SELECT * FROM product WHERE package_name = :packageName")
    fun get(packageName: String): List<Product?>

    @Query("DELETE FROM product WHERE repository_id = :id")
    fun deleteById(vararg id: Long): Int

    @RawQuery
    fun query(
        query: SupportSQLiteQuery
    ): Cursor

    // TODO optimize and simplify
    @Transaction
    fun query(
        installed: Boolean, updates: Boolean, searchQuery: String,
        section: ProductItem.Section, order: ProductItem.Order, signal: CancellationSignal?
    ): Cursor {
        val builder = QueryBuilder()

        val signatureMatches = """installed.${ROW_SIGNATURE} IS NOT NULL AND
        product.${ROW_SIGNATURES} LIKE ('%.' || installed.${ROW_SIGNATURE} || '.%') AND
        product.${ROW_SIGNATURES} != ''"""

        builder += """SELECT product.rowid AS _id, product.${ROW_REPOSITORY_ID},
        product.${ROW_PACKAGE_NAME}, product.${ROW_NAME},
        product.${ROW_SUMMARY}, installed.${ROW_VERSION},
        (COALESCE(lock.${ROW_VERSION_CODE}, -1) NOT IN (0, product.${ROW_VERSION_CODE}) AND
        product.${ROW_COMPATIBLE} != 0 AND product.${ROW_VERSION_CODE} >
        COALESCE(installed.${ROW_VERSION_CODE}, 0xffffffff) AND $signatureMatches)
        AS ${ROW_CAN_UPDATE}, product.${ROW_COMPATIBLE},
        product.${ROW_DATA_ITEM},"""

        if (searchQuery.isNotEmpty()) {
            builder += """(((product.${ROW_NAME} LIKE ? OR
          product.${ROW_SUMMARY} LIKE ?) * 7) |
          ((product.${ROW_PACKAGE_NAME} LIKE ?) * 3) |
          (product.${ROW_DESCRIPTION} LIKE ?)) AS ${ROW_MATCH_RANK},"""
            builder %= List(4) { "%$searchQuery%" }
        } else {
            builder += "0 AS ${ROW_MATCH_RANK},"
        }

        builder += """MAX((product.${ROW_COMPATIBLE} AND
        (installed.${ROW_SIGNATURE} IS NULL OR $signatureMatches)) ||
        PRINTF('%016X', product.${ROW_VERSION_CODE})) FROM $ROW_PRODUCT_NAME AS product"""
        builder += """JOIN $ROW_REPOSITORY_NAME AS repository
        ON product.${ROW_REPOSITORY_ID} = repository.${ROW_ID}"""
        builder += """LEFT JOIN $ROW_LOCK_NAME AS lock
        ON product.${ROW_PACKAGE_NAME} = lock.${ROW_PACKAGE_NAME}"""

        if (!installed && !updates) {
            builder += "LEFT"
        }
        builder += """JOIN $ROW_INSTALLED_NAME AS installed
        ON product.${ROW_PACKAGE_NAME} = installed.${ROW_PACKAGE_NAME}"""

        if (section is ProductItem.Section.Category) {
            builder += """JOIN $ROW_CATEGORY_NAME AS category
          ON product.${ROW_PACKAGE_NAME} = category.${ROW_PACKAGE_NAME}"""
        }

        builder += """WHERE repository.${ROW_ENABLED} != 0 AND
        repository.${ROW_DELETED} == 0"""

        if (section is ProductItem.Section.Category) {
            builder += "AND category.${ROW_NAME} = ?"
            builder %= section.name
        } else if (section is ProductItem.Section.Repository) {
            builder += "AND product.${ROW_REPOSITORY_ID} = ?"
            builder %= section.id.toString()
        }

        if (searchQuery.isNotEmpty()) {
            builder += """AND $ROW_MATCH_RANK > 0"""
        }

        builder += "GROUP BY product.${ROW_PACKAGE_NAME} HAVING 1"

        if (updates) {
            builder += "AND $ROW_CAN_UPDATE"
        }
        builder += "ORDER BY"

        if (searchQuery.isNotEmpty()) {
            builder += """$ROW_MATCH_RANK DESC,"""
        }

        when (order) {
            ProductItem.Order.NAME -> Unit
            ProductItem.Order.DATE_ADDED -> builder += "product.${ROW_ADDED} DESC,"
            ProductItem.Order.LAST_UPDATE -> builder += "product.${ROW_UPDATED} DESC,"
        }::class
        builder += "product.${ROW_NAME} COLLATE LOCALIZED ASC"

        return query(SimpleSQLiteQuery(builder.build()))
    }
}

@Dao
interface CategoryDao : BaseDao<Category> {
    @get:Query(
        """SELECT DISTINCT category.name
        FROM category AS category
        JOIN repository AS repository
        ON category.repository_id = repository._id
        WHERE repository.enabled != 0 AND
        repository.deleted == 0"""
    )
    val allNames: List<String>

    @Query("DELETE FROM category WHERE repository_id = :id")
    fun deleteById(vararg id: Long): Int
}

@Dao
interface InstalledDao : BaseDao<Installed> {
    fun put(vararg isntalled: com.looker.droidify.entity.InstalledItem) {
        isntalled.forEach {
            insert(Installed(it.packageName).apply {
                version = it.version
                version_code = it.versionCode
                signature = it.signature
            })
        }
    }

    @Query("SELECT * FROM memory_installed WHERE package_name = :packageName")
    fun get(packageName: String): Cursor

    @Query("DELETE FROM memory_installed WHERE package_name = :packageName")
    fun delete(packageName: String)
}

@Dao
interface LockDao : BaseDao<Lock> {
    @Query("DELETE FROM memory_lock WHERE package_name = :packageName")
    fun delete(packageName: String)
}

@Dao
interface ProductTempDao : BaseDao<ProductTemp> {
    @get:Query("SELECT * FROM temporary_product")
    val all: Array<ProductTemp>

    @Query("DELETE FROM temporary_product")
    fun emptyTable()

    @Insert
    fun insertCategory(vararg product: CategoryTemp)

    @Transaction
    fun putTemporary(products: List<com.looker.droidify.entity.Product>) {
        products.forEach {
            val signatures = it.signatures.joinToString { ".$it" }
                .let { if (it.isNotEmpty()) "$it." else "" }
            insert(it.let {
                ProductTemp().apply {
                    repository_id = it.repositoryId
                    package_name = it.packageName
                    name = it.name
                    summary = it.summary
                    description = it.description
                    added = it.added
                    updated = it.updated
                    version_code = it.versionCode
                    this.signatures = signatures
                    compatible = if (it.compatible) 1 else 0
                    data = it
                    data_item = it.item()
                }
            })
            it.categories.forEach { category ->
                insertCategory(CategoryTemp().apply {
                    repository_id = it.repositoryId
                    package_name = it.packageName
                    name = category
                })
            }
        }
    }
}

@Dao
interface CategoryTempDao : BaseDao<CategoryTemp> {
    @get:Query("SELECT * FROM temporary_category")
    val all: Array<CategoryTemp>

    @Query("DELETE FROM temporary_category")
    fun emptyTable()
}