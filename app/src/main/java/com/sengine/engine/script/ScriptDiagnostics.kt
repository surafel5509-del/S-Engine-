package com.sengine.engine.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.ast.AstRoot
import org.mozilla.javascript.ast.FunctionNode
import org.mozilla.javascript.Parser
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.ErrorReporter
import org.mozilla.javascript.ast.NodeVisitor

/** IDE diagnostics never execute user code. */
object ScriptDiagnostics {
    data class Issue(val file: String, val line: Int, val column: Int, val message: String, val severity: String = "error")
    data class Report(val issues: List<Issue>, val functions: List<String>) { val ok get() = issues.none { it.severity == "error" } }
    const val MAX_SOURCE_BYTES = 512 * 1024
    fun validate(file: String, source: String): Report {
        if(source.toByteArray().size > MAX_SOURCE_BYTES) return Report(listOf(Issue(file,1,1,"Script exceeds 512 KB")),emptyList())
        val cx=Context.enter()
        return try {
            cx.optimizationLevel=-1;cx.languageVersion=Context.VERSION_ES6
            val environment=CompilerEnvirons().also{it.languageVersion=Context.VERSION_ES6;it.isRecordingComments=false;it.errorReporter=object:ErrorReporter{override fun warning(m:String,s:String,l:Int,ls:String,c:Int){} override fun error(m:String,s:String,l:Int,ls:String,c:Int){throw EvaluatorException(m,s,l,ls,c)} override fun runtimeError(m:String,s:String,l:Int,ls:String,c:Int):EvaluatorException=throw EvaluatorException(m,s,l,ls,c)}}
            val ast=Parser(environment).parse(source,file,1)
            val functions=ArrayList<String>()
            ast.visitAll(object:NodeVisitor{override fun visit(node:org.mozilla.javascript.ast.AstNode):Boolean{if(node is FunctionNode && node.name.isNotEmpty())functions.add(node.name);return true}})
            val warnings=ArrayList<Issue>()
            if(source.contains("Packages.") || source.contains("java.lang.") || source.contains("getClass(")) warnings+=Issue(file,1,1,"Host Java access is blocked. Use the documented engine API.","warning")
            Report(warnings,functions)
        } catch(e:EvaluatorException) {
            Report(listOf(Issue(file,e.lineNumber().coerceAtLeast(1),e.columnNumber().coerceAtLeast(1),e.details())),emptyList())
        } finally {Context.exit()}
    }
}

/** Pure parser for inspector parameters; quoted commas and escaped strings are preserved. */
object ScriptParameters {
    fun parse(text:String):Map<String,Any> {
        val out=LinkedHashMap<String,Any>();var i=0
        while(i<text.length){
            while(i<text.length && (text[i].isWhitespace()||text[i] in ",;"))i++
            val start=i;while(i<text.length && text[i]!='=')i++
            if(i>=text.length)break
            val key=text.substring(start,i).trim();i++
            while(i<text.length && text[i].isWhitespace())i++
            val quote=text.getOrNull(i)?.takeIf{it=='\''||it=='"'}
            val value=if(quote!=null){
                i++;val b=StringBuilder();var closed=false
                while(i<text.length){val c=text[i++];if(c==quote){closed=true;break};if(c=='\\'&&i<text.length){b.append(when(text[i]){ 'n'->'\n';'t'->'\t';else->text[i]});i++}else b.append(c)}
                require(closed){"Unclosed string for '$key'"};b.toString()
            }else{val s=i;while(i<text.length&&text[i] !in ",;\n")i++;text.substring(s,i).trim()}
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && key !in setOf("self","transform","gameObject","scene","input","time","console","__proto__","prototype","constructor")){"Invalid parameter name '$key'"}
            val v:Any=if(quote!=null)value else value.toDoubleOrNull()?.takeIf{it.isFinite()} ?: when(value.lowercase()){"true"->true;"false"->false;else->value}
            out[key]=v
        }
        require(out.size<=64){"At most 64 script parameters"}
        return out
    }
}
