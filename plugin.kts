
import Plugin.ReplaceFromIntention
import Plugin.ReplaceSelectIntention
import ai.grazie.utils.LinkedSet
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.childrenOfType
import com.intellij.psi.util.parentOfType
import com.intellij.sql.SqlFileType
import com.intellij.sql.child
import com.intellij.sql.psi.*
import com.intellij.sql.psi.impl.*
import liveplugin.registerIntention
import liveplugin.show
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.stream.Collectors


// depends-on-plugin com.intellij.database

if (!isIdeStartup) {
    show("Loaded...")
}


registerIntention(ReplaceKeywordIntention())

registerIntention(ReplaceFromIntention())
registerIntention(ReplaceSelectIntention())

/*
样例json
{
  "language" :"Oracle",
  "replaceCols": [
    {
      "key": "关键字(列别名)",
      "value": "显示的值", // 这里可以用#col#去取列名 #table#去取表名 不写默认为 "#table_alias#.#col#"
      "col": "列名",
      "table": "表名",
      "table_alias":"表别名"
    }
  ]
}
* */

// 替换成自己的配置文件所在目录
val pluginDir = "..\\live-plugins\\YxSqlReplaceKeyWord"


val gson = Gson()

class MyProps {
    private val replacePropJsonString: String =
        FileUtils.readFileToString(File(pluginDir, "props\\replaceProp.json"), Charsets.UTF_8)
    private val tableRelationPropJsonString: String =
        FileUtils.readFileToString(File(pluginDir, "props\\tableRelationProp.json"), Charsets.UTF_8)

    data class ReplaceCol(@SerializedName("col") var col: String,
                          @SerializedName("key") var key: String,
                          @SerializedName("table") var table: String,
                          @SerializedName("table_alias") var tableAlias: String,
                          @SerializedName("value") var value: String?)

    data class ReplaceProp(var language: String, var replaceCols: List<ReplaceCol>)

    val replaceProp: ReplaceProp = gson.fromJson(replacePropJsonString, ReplaceProp::class.java)
    val replaceKeys: List<String> = replaceProp.replaceCols.map { it.key }.toList()
    val replaceMap: Map<String, ReplaceCol> =
        replaceProp.replaceCols.stream().collect(Collectors.toMap({ it.key }, { it }))

    data class TableRelationProp(
        @SerializedName("name")
        var name: String,
        @SerializedName("relation")
        var relation: List<RelationProp>
    )

    data class RelationProp(@SerializedName("b_alias") var bAlias: String,
                            @SerializedName("baseTable") var baseTable: String,
                            @SerializedName("relations") var relations: List<Relation>)

    data class Relation(
        @SerializedName("a_alias")
        var aAlias: String,
        @SerializedName("associatedTable")
        var associatedTable: String,
        @SerializedName("conditions")
        var conditions: String,
        @SerializedName("type")
        var type: String
    )

    val tableRelationProp: TableRelationProp = gson.fromJson(tableRelationPropJsonString, TableRelationProp::class.java)

}

val myProps = MyProps()



if (!isIdeStartup) {
    show("Loaded...")
}


/**
 * 获取替换后的value
 *
 * 将#col#替换成对应取值
 *
 * @return [String]
 */
fun MyProps.ReplaceCol.getValueStr(): String {
    return when {
        value.isNullOrBlank() -> {
            "${this.tableAlias}.${this.col}"
        }

        else -> {
            this.value!!.replace("#col#", this.col)
                .replace("#table#", this.table)
                .replace("#table_alias#", this.tableAlias)
        }
    }
}

/**
 * Replace keyword intention
 *
 * 单列字段替换
 *
 * @constructor Create empty Replace keyword intention
 */
class ReplaceKeywordIntention : PsiElementBaseIntentionAction() {

    /**
     * 判断哪里可以触发意图
     *
     * @param project
     * @param editor
     * @param element
     * @return
     */
    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        try {
            if (element.isInOracleFile() && element.isReplaceableWord() && myProps.replaceKeys.contains(element.text)) {
                return true
            }
        } catch (e: Exception) {
            show("Error: ${e.message}")
            throw e
        }
        return false
    }

    /**
     * 意图触发的时候干什么
     *
     * @param project
     * @param editor
     * @param element
     */
    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        try {
            if (element is SqlTokenElement && myProps.replaceKeys.contains(element.text)) {
                replaceCol(element)
            }
        } catch (e: Exception) {
            show("Error: ${e.message}")
            throw e
        }

    }

    /**
     * 如果此操作适用，则返回要显示在可用意向操作列表中的文本。
     */
    override fun getText() = "YxReplace"

    /**
     * 返回此意向族名称的文本。
     * 它被用来外化意图的“车展”状态。
     * 它也是描述的目录名。
     *
     * @return the intention family name.
     */
    override fun getFamilyName() = "YxReplace family"

}

fun PsiElement.isInOracleFile(): Boolean {
    val fileType = containingFile?.fileType ?: return false
    if (fileType is LanguageFileType) {
        if (fileType.language.id == "SQL" && fileType is SqlFileType) {
            return language.id == "Oracle"
        }
    }
    return false
}

fun replaceCol(element: SqlTokenElement) {
    val colRef = PsiTreeUtil.getParentOfType(element, SqlReferenceExpressionImpl::class.java)!!

    val str = "${myProps.replaceMap[element.text]?.getValueStr()} ${element.text}"
    // 通过文本生成列别名
    val expression = SqlPsiElementFactory.createColumnAliasFromText(str, element.language, element)!!
    colRef.replace(expression)
}

fun PsiElement.isReplaceableWord(): Boolean {
    return parentOfType<SqlIdentifier>() != null && parentOfType<SqlReferenceExpression>() != null && parentOfType<SqlSelectClauseImpl>() != null && myProps.replaceMap.containsKey(
            text)
}

/**
 * Get sql root element
 * 获取SQL_SELECT_STATEMENT
 * @param [element]
 * @return [SqlSelectStatementImpl]
 */
fun getSqlRootElement(element: PsiElement): SqlSelectStatementImpl {
    return PsiTreeUtil.getParentOfType(element, SqlSelectStatementImpl::class.java)!!
}

/**
 * 获取 SQL_SELECT_CLAUSE
 *
 * select xxx, xxx
 * @param [root]
 * @return [SqlSelectClauseImpl]
 */
fun getSqlSelectClauseElement(root: SqlSelectStatementImpl): SqlSelectClauseImpl {
    val sqlQuery = PsiTreeUtil.getRequiredChildOfType(root, SqlQueryExpressionImpl::class.java)
    return PsiTreeUtil.getRequiredChildOfType(sqlQuery, SqlSelectClauseImpl::class.java)
}

/**
 * 获取 SQL_TABLE_EXPRESSION
 *
 * from xxx where xxx
 * @param [root]
 * @return [SqlTableExpressionImpl]
 */
fun getSqlTableExpressionElement(root: SqlSelectStatementImpl): SqlTableExpressionImpl {
    val sqlQuery = PsiTreeUtil.getRequiredChildOfType(root, SqlQueryExpressionImpl::class.java)
    return PsiTreeUtil.getRequiredChildOfType(sqlQuery, SqlTableExpressionImpl::class.java)
}

/**
 * 获取 SQL_FROM_CLAUSE
 *
 * from xxx
 *
 * @param [root]
 * @return [SqlFromClauseImpl]
 */
fun getSqlFromClauseElement(root: SqlTableExpressionImpl): SqlFromClauseImpl {
    return PsiTreeUtil.getRequiredChildOfType(root, SqlFromClauseImpl::class.java)
}

//fun getSqlFromClauseElement(root: SqlSelectStatementImpl): SqlFromClauseImpl {
//  val sqlTableExpressionElement = getSqlTableExpressionElement(root)
//  return PsiTreeUtil.getRequiredChildOfType(sqlTableExpressionElement, SqlFromClauseImpl::class.java)
//}

fun SqlSelectStatementImpl.getSqlFromClauseElement(): SqlFromClauseImpl {
    val sqlTableExpressionElement = getSqlTableExpressionElement(this)
    return PsiTreeUtil.getRequiredChildOfType(sqlTableExpressionElement, SqlFromClauseImpl::class.java)
}


/**
 * 获取 from 关键字
 * @param [root]
 * @return [SqlTokenElement?]
 */
fun getSqlKeyFromElement(root: SqlFromClauseImpl): SqlTokenElement? {
    return PsiTreeUtil.findChildOfType(root, SqlTokenElement::class.java)
}


fun PsiElement.isFromKeyword(): Boolean {
    return parentOfType<SqlFromClauseImpl>() != null && parentOfType<SqlTableExpressionImpl>() != null && parentOfType<SqlSelectStatementImpl>() != null && text.equals(
            "from",
            ignoreCase = true)
}

fun PsiElement.isSelectKeyword(): Boolean {
    return parentOfType<SqlSelectClauseImpl>() != null && parentOfType<SqlQueryExpressionImpl>() != null && parentOfType<SqlSelectStatementImpl>() != null && text.equals(
            "select",
            ignoreCase = true)
}

/**
 * 根据列内容 生成from内容
 *
 * @author admin
 * @date 2023/06/12
 * @constructor 创建[ReplaceFromIntention]
 */
class ReplaceFromIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "YxReplace family"

    override fun getText(): String {
        return "ReplaceFrom"
    }

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        try {
            if (element.isInOracleFile() && element.isFromKeyword()) {
                return true
            }
        } catch (e: Exception) {
            show("Error: ${e.message}")
            throw e
        }
        return false
    }


    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        try {
            if (element is SqlTokenElement) {
                // 1.获取列信息
                val rootElement = getSqlRootElement(element)
                val sqlSelectClauseElement = getSqlSelectClauseElement(rootElement)
                // 表部分字符串
                val tableMap = LinkedHashMap<String, String>()
                // 获取里面所有的as
                val sqlAsExprElements = sqlSelectClauseElement.childrenOfType<SqlAsExpressionImpl>()
                sqlAsExprElements.forEach {
                    val colKey = it.lastChild!!.child<SqlTokenElement>()!!
                    myProps.replaceMap[colKey.text]?.let { col ->
                        if (!tableMap.containsKey(col.table.uppercase())) {
                            tableMap[col.table.uppercase()] = col.tableAlias
                        }
                    }
                }

                // 2.生成from语句
                val sqlFromClause = PsiTreeUtil.getParentOfType(element, SqlFromClauseImpl::class.java)!!


                val tableStr = getTableStr(tableMap)


                // 生成一个全新的语句 去获取from部分 用注释去保留旧的from
                val selectStatement =
                    SqlPsiElementFactory.createStatementFromText("SELECT * /*${sqlFromClause.text}*/ from $tableStr",
                            element.language,
                            project,
                            sqlFromClause) as SqlSelectStatementImpl
                val oldFrom =
                    PsiTreeUtil.getChildOfAnyType(selectStatement.child<SqlQueryExpressionImpl>(),
                            PsiCommentImpl::class.java,
                            PsiComment::class.java)!!
                rootElement.child<SqlQueryExpressionImpl>()?.add(oldFrom)

                sqlFromClause.replace(selectStatement.getSqlFromClauseElement())
            }
        } catch (e: Exception) {
            show("Error: ${e.message}")
            throw e
        }
    }

    /**
     * 获取tableStr
     *
     *
     * @param [tableMap]
     * @return [String?]
     */
    private fun getTableStr(tableMap: LinkedHashMap<String, String>): String? {
        /*
            遍历relation
            如果baseTable在map中
                则遍历relations
                    如果associatedTable在map中 则拼接 type conditions 如果出现已经参与拼接的表 则不再拼接跳过该表
               在relation中找到baseTable=associatedTable的元素 遍历relations
        */
        val tableSb: StringBuilder = StringBuilder()

        val usedTable = LinkedSet<String>()

        fun relationForEach(base: MyProps.RelationProp) {
            println("base: ${base.baseTable} usedTable:${usedTable}")
            if (!usedTable.contains(base.baseTable.uppercase()) && tableMap.containsKey(base.baseTable.uppercase())) {
                tableSb.append(when {
                    // 第一个表的额外处理
                    usedTable.isEmpty() -> "${base.baseTable} ${tableMap[base.baseTable.uppercase()]}\n"
                    else -> ", ${base.baseTable} ${tableMap[base.baseTable.uppercase()]}\n"
                })
                usedTable.add(base.baseTable.uppercase())
            }
            if (tableMap.containsKey(base.baseTable.uppercase())){
                base.relations.forEach { asso ->
                    println("asso: ${asso.associatedTable} usedTable:${usedTable}")
                    if (!usedTable.contains(asso.associatedTable.uppercase()) && tableMap.containsKey(asso.associatedTable.uppercase())) {
                        tableSb.append("${asso.type} ${asso.associatedTable} ${tableMap[asso.associatedTable.uppercase()]} \n" +
                                "  on ${
                                    asso.conditions.replace("#a_alias#", asso.aAlias)
                                        .replace("#b_alias#", base.bAlias)
                                } \n")
                        usedTable.add(asso.associatedTable.uppercase())

                        // 在所有表配置里找当前表
                        myProps.tableRelationProp.relation.find { it.baseTable.uppercase() == asso.associatedTable.uppercase() }
                            .let {
                                println("find next ${it?.baseTable} ${asso.associatedTable}")
                                if (it != null) {
                                    relationForEach(it)
                                    println("foreach end ${it.baseTable}")
                                }
                            }
                    }
                }
            }
        }

        myProps.tableRelationProp.relation.forEach { relationForEach(it) }

        if (usedTable.size != tableMap.size) {
            println("....usedTable:${usedTable} tableMap: ${tableMap.keys}")
            if (tableSb.isNotEmpty()) {
                tableSb.append(",")
            }
            tableSb.append(tableMap.filter { !usedTable.contains(it.key) }
                .map { "${it.key} ${it.value}" }
                .joinToString("\n,"))
        }

        return tableSb.toString()
    }

}

/**
 * 根据列内容生成select
 * @author admin
 * @date 2023/06/12
 * @constructor 创建[ReplaceSelectIntention]
 */
class ReplaceSelectIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "YxReplace family"

    override fun getText(): String = "ReplaceSelect"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        try {
            if (element.isInOracleFile() && element.isSelectKeyword()) {
                return true
            }
        } catch (e: Exception) {
            show("Error: ${e.message}")
            throw e
        }

        return false
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        try {
            if (element.isInOracleFile() && element.isSelectKeyword()) {

                val root = getSqlRootElement(element)
                val sqlSelectClauseElement = getSqlSelectClauseElement(root)
                val colList =
                    PsiTreeUtil.getChildrenOfTypeAsList(sqlSelectClauseElement, SqlReferenceExpressionImpl::class.java)
                colList.stream()
                    .filter { it.elementType == SqlElementTypes.SQL_COLUMN_REFERENCE }
                    .map { it.child<SqlIdentifierImpl>()!!.child<SqlTokenElement>()!! }
                    .forEach { replaceCol(it) }

            }
        } catch (e: Exception) {
            show("Error: ${e.message}")
            throw e
        }

    }
}

