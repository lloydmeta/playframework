/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.libs.json

import scala.language.higherKinds
import scala.reflect.macros._
import language.experimental.macros

object JsMacroImpl {

  // <-- Package references
  private def libsPkg(c: Context): c.universe.Select = {
    import c.universe._
    Select(Select(Ident(newTermName("play")), newTermName("api")), newTermName("libs"))
  }

  private def jsonPkg(c: Context): c.universe.Select = {
    import c.universe._
    Select(libsPkg(c), newTermName("json"))
  }

  private def functionalSyntaxPkg(c: Context) = {
    import c.universe._
    Select(Select(libsPkg(c), newTermName("functional")), newTermName("syntax"))
  }

  private def utilPkg(c: Context) = {
    import c.universe._
    Select(jsonPkg(c), newTermName("util"))
  }

  private def lazyHelper(c: Context) = {
    import c.universe._
    Select(utilPkg(c), newTypeName("LazyHelper"))
  }
  // Package references -->

  def formatImpl[A: c.WeakTypeTag](c: Context): c.Expr[Format[A]] =
    macroImpl[A, Format](c, "format", "inmap", reads = true, writes = true)

  def readsImpl[A: c.WeakTypeTag](c: Context): c.Expr[Reads[A]] =
    macroImpl[A, Reads](c, "read", "map", reads = true, writes = false)

  def writesImpl[A: c.WeakTypeTag](c: Context): c.Expr[Writes[A]] =
    macroImpl[A, Writes](c, "write", "contramap", reads = false, writes = true)

  def writesGettersImpl[A: c.WeakTypeTag](c: Context): c.Expr[Writes[A]] = {
    import c.universe._
    import c.universe.Flag._

    val typeToWrite = c.weakTypeOf[A]
    val getterMethodSymbols = for {
      memberSymbol <- typeToWrite.members
      term = memberSymbol.asTerm
      if term.isPublic && term.isGetter
    } yield memberSymbol
    if (getterMethodSymbols.isEmpty)
      c.abort(c.enclosingPosition, s"${typeToWrite} has no getters. Are you using the right class?")
    val getterMethodTerms = getterMethodSymbols.map(_.asTerm)

    val (canBuildFrom, hasRec) = canBuildTree[A, Writes](c)(
      methodName = "write",
      reads = false,
      writes = true,
      typeToWrite = typeToWrite.typeSymbol,
      accessorSymbols = getterMethodSymbols.toSeq,
      hasVarArgs = false,
      unapplyReturnTypes = None)

    // Manually-built "unapply"
    val objectTerm = newTermName("o")
    val objectIdent = Ident(objectTerm)
    val unapplyExpressions = getterMethodTerms.map { term => Select(objectIdent, term) }
    val unapplyWrapper = if (unapplyExpressions.size > 1)
      Select(Ident(newTermName("scala")), newTermName(s"Tuple${getterMethodTerms.size}"))
    else
      Ident(newTermName("identity")) // If only 1 term, don't put in a tuple
    val unapplyBody = Function(
      List(ValDef(Modifiers(PARAM), objectTerm, TypeTree(typeToWrite), EmptyTree)),
      Apply(
        unapplyWrapper,
        unapplyExpressions.toList
      )
    )

    val applier = newTermName(if (getterMethodTerms.size > 1) "apply" else "contramap")
    val unliftIdent = Select(functionalSyntaxPkg(c), newTermName("unlift"))
    val finalTree = Apply(
      Select(canBuildFrom, applier),
      List(
        unapplyBody
      )
    )

    val block = buildFinalBlock[A, Writes](c)(finalTree, hasRec)
    c.Expr[Writes[A]](block)
  }

  def macroImpl[A, M[_]](c: Context, methodName: String, mapLikeMethod: String, reads: Boolean, writes: Boolean)(implicit atag: c.WeakTypeTag[A], matag: c.WeakTypeTag[M[A]]): c.Expr[M[A]] = {

    def conditionalList[T](ifReads: T, ifWrites: T): List[T] =
      (if (reads) List(ifReads) else Nil) :::
        (if (writes) List(ifWrites) else Nil)

    import c.universe._
    import c.universe.Flag._

    val companioned = weakTypeOf[A].typeSymbol
    val companionSymbol = companioned.companionSymbol
    val companionType = companionSymbol.typeSignature

    val unliftIdent = Select(functionalSyntaxPkg(c), newTermName("unlift"))

    val unapply = companionType.declaration(stringToTermName("unapply"))
    val unapplySeq = companionType.declaration(stringToTermName("unapplySeq"))
    val hasVarArgs = unapplySeq != NoSymbol

    val effectiveUnapply = Seq(unapply, unapplySeq).filter(_ != NoSymbol).headOption match {
      case None => c.abort(c.enclosingPosition, "No unapply or unapplySeq function found")
      case Some(s) => s.asMethod
    }

    val unapplyReturnTypes: Option[List[Type]] = effectiveUnapply.returnType match {
      case TypeRef(_, _, Nil) => {
        c.abort(c.enclosingPosition, s"Unapply of ${companionSymbol} has no parameters. Are you using an empty case class?")
        None
      }
      case TypeRef(_, _, args) =>
        args.head match {
          case t @ TypeRef(_, _, Nil) => Some(List(t))
          case t @ TypeRef(_, _, args) => {
            import c.universe.definitions.TupleClass
            if (!TupleClass.seq.exists(tupleSym => t.baseType(tupleSym) ne NoType)) Some(List(t))
            else if (t <:< typeOf[Product]) Some(args)
            else None
          }
          case _ => None
        }
      case _ => None
    }

    //println("Unapply return type:" + unapplyReturnTypes)

    val applies =
      companionType.declaration(stringToTermName("apply")) match {
        case NoSymbol => c.abort(c.enclosingPosition, "No apply function found")
        case s => s.asMethod.alternatives
      }

    // searches apply method corresponding to unapply
    val apply = applies.collectFirst {
      case (apply: MethodSymbol) if hasVarArgs && {
        val someApplyTypes = apply.paramss.headOption.map(_.map(_.asTerm.typeSignature))
        val someInitApply = someApplyTypes.map(_.init)
        val someApplyLast = someApplyTypes.map(_.last)
        val someInitUnapply = unapplyReturnTypes.map(_.init)
        val someUnapplyLast = unapplyReturnTypes.map(_.last)
        val initsMatch = someInitApply == someInitUnapply
        val lastMatch = (for {
          lastApply <- someApplyLast
          lastUnapply <- someUnapplyLast
        } yield lastApply <:< lastUnapply).getOrElse(false)
        initsMatch && lastMatch
      } => apply
      case (apply: MethodSymbol) if (apply.paramss.headOption.map(_.map(_.asTerm.typeSignature)) == unapplyReturnTypes) => apply
    }

    val params = apply match {
      case Some(apply) => apply.paramss.head //verify there is a single parameter group
      case None => c.abort(c.enclosingPosition, "No apply function found matching unapply parameters")
    }

    //println("apply found:" + apply)

    // builds the final M[A] using apply method
    //val applyMethod = Ident( companionSymbol )
    val applyBody = {
      val body = params.foldLeft(List[Tree]())((l, e) =>
        l :+ Ident(newTermName(e.name.encoded))
      )
      if (hasVarArgs)
        body.init :+ Typed(body.last, Ident(tpnme.WILDCARD_STAR))
      else body
    }
    val applyMethod =
      Function(
        params.foldLeft(List[ValDef]())((l, e) =>
          l :+ ValDef(Modifiers(PARAM), newTermName(e.name.encoded), TypeTree(), EmptyTree)
        ),
        Apply(
          Select(Ident(companionSymbol), newTermName("apply")),
          applyBody
        )
      )

    val unapplyMethod = Apply(
      unliftIdent,
      List(
        Select(Ident(companionSymbol), effectiveUnapply.name)
      )
    )

    val (canBuildFrom, hasRec) = canBuildTree[A, M](c)(
      methodName = methodName,
      reads = reads,
      writes = writes,
      typeToWrite = companioned,
      accessorSymbols = params,
      hasVarArgs = hasVarArgs,
      unapplyReturnTypes = unapplyReturnTypes)

    // if case class has one single field, needs to use inmap instead of canbuild.apply
    val method = if (params.length > 1) "apply" else mapLikeMethod
    val finalTree = Apply(
      Select(canBuildFrom, newTermName(method)),
      conditionalList(applyMethod, unapplyMethod)
    )
    //println("finalTree: "+finalTree)

    val block = buildFinalBlock[A, M](c)(finalTree, hasRec)
    c.Expr[M[A]](block)
  }

  private def canBuildTree[A, M[_]](c: Context)(
    methodName: String,
    reads: Boolean,
    writes: Boolean,
    typeToWrite: c.universe.Symbol,
    accessorSymbols: Seq[c.universe.Symbol],
    hasVarArgs: Boolean,
    unapplyReturnTypes: Option[List[c.universe.Type]])(implicit atag: c.WeakTypeTag[A], matag: c.WeakTypeTag[M[A]]): (c.universe.Apply, Boolean) = {

    import c.universe._

    def conditionalList[T](ifReads: T, ifWrites: T): List[T] =
      (if (reads) List(ifReads) else Nil) :::
        (if (writes) List(ifWrites) else Nil)

    val nullableMethodName = s"${methodName}Nullable"
    val lazyMethodName = s"lazy${methodName.capitalize}"

    val jsPathSelect = Select(jsonPkg(c), newTermName("JsPath"))
    val readsSelect = Select(jsonPkg(c), newTermName("Reads"))
    val writesSelect = Select(jsonPkg(c), newTermName("Writes"))

    final case class Implicit(paramName: Name, paramType: Type, neededImplicit: Tree, isRecursive: Boolean, tpe: Type)

    val createImplicit = { (name: Name, implType: c.universe.Type) =>
      val (isRecursive, tpe) = implType match {
        case TypeRef(_, t, args) =>
          val isRec = args.exists(_.typeSymbol == typeToWrite)
          // Option[_] needs special treatment because we need to use XXXOpt
          val tp = if (implType.typeConstructor <:< typeOf[Option[_]].typeConstructor) args.head else implType
          (isRec, tp)
        case TypeRef(_, t, _) =>
          (false, implType)
      }

      // builds M implicit from expected type
      val neededImplicitType = appliedType(matag.tpe.typeConstructor, tpe :: Nil)
      // infers implicit
      val neededImplicit = c.inferImplicitValue(neededImplicitType)
      Implicit(name, implType, neededImplicit, isRecursive, tpe)
    }

    val applyParamImplicits = accessorSymbols.map { accessor =>
      // For accesors that are methods (vals in the body), we only care about the return type
      val implType = if (accessor.isMethod) accessor.asMethod.returnType else accessor.typeSignature
      createImplicit(accessor.name, implType)
    }
    val effectiveInferredImplicits = if (hasVarArgs) {
      val varArgsImplicit = createImplicit(applyParamImplicits.last.paramName, unapplyReturnTypes.get.last)
      applyParamImplicits.init :+ varArgsImplicit
    } else applyParamImplicits

    // if any implicit is missing, abort
    val missingImplicits = effectiveInferredImplicits.collect { case Implicit(_, t, impl, rec, _) if (impl == EmptyTree && !rec) => t }
    if (missingImplicits.nonEmpty)
      c.abort(c.enclosingPosition, s"No implicit format for ${missingImplicits.mkString(", ")} available.")

    val helperMember = Select(This(tpnme.EMPTY), newTermName("lazyStuff"))
    def callHelper(target: Tree, methodName: String): Tree =
      Apply(Select(target, newTermName(methodName)), List(helperMember))
    def readsWritesHelper(methodName: String): List[Tree] =
      conditionalList(readsSelect, writesSelect).map(s => callHelper(s, methodName))

    var hasRec = false

    // combines all reads into CanBuildX
    val canBuild = effectiveInferredImplicits.map {
      case Implicit(name, t, impl, rec, tpe) =>
        // inception of (__ \ name).read(impl)
        val jspathTree = Apply(
          Select(jsPathSelect, newTermName(scala.reflect.NameTransformer.encode("\\"))),
          List(Literal(Constant(name.decoded)))
        )

        if (!rec) {
          val callMethod = if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor) nullableMethodName else methodName
          Apply(
            Select(jspathTree, newTermName(callMethod)),
            List(impl)
          )
        } else {
          hasRec = true
          if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
            Apply(
              Select(jspathTree, newTermName(nullableMethodName)),
              callHelper(Apply(jsPathSelect, Nil), lazyMethodName) :: Nil
            )
          else {
            Apply(
              Select(jspathTree, newTermName(lazyMethodName)),
              if (tpe.typeConstructor <:< typeOf[List[_]].typeConstructor)
                readsWritesHelper("list")
              else if (tpe.typeConstructor <:< typeOf[Set[_]].typeConstructor)
                readsWritesHelper("set")
              else if (tpe.typeConstructor <:< typeOf[Seq[_]].typeConstructor)
                readsWritesHelper("seq")
              else if (tpe.typeConstructor <:< typeOf[Map[_, _]].typeConstructor)
                readsWritesHelper("map")
              else List(helperMember)
            )
          }
        }
    }.reduceLeft { (acc, r) =>
      Apply(
        Select(acc, newTermName("and")),
        List(r)
      )
    }
    //println(s"canBuildTree returns: $canBuild, $hasRec")
    (canBuild, hasRec)
  }

  private def buildFinalBlock[A, M[_]](c: Context)(finalTree: c.universe.Tree, hasRec: Boolean)(implicit atag: c.WeakTypeTag[A], matag: c.WeakTypeTag[M[A]]) = {
    import c.universe._
    val lazyHelperSelect = lazyHelper(c)
    val importFunctionalSyntax = Import(functionalSyntaxPkg(c), List(ImportSelector(nme.WILDCARD, -1, null, -1)))
    if (!hasRec) {
      val block = Block(
        List(importFunctionalSyntax),
        finalTree
      )
      block
    } else {
      val helper = newTermName("helper")
      val helperVal = ValDef(
        Modifiers(),
        helper,
        Ident(weakTypeOf[play.api.libs.json.util.LazyHelper[M, A]].typeSymbol),
        Apply(Ident(newTermName("LazyHelper")), List(finalTree))
      )

      val block = Select(
        Block(
          List(
            importFunctionalSyntax,
            ClassDef(
              Modifiers(Flag.FINAL),
              newTypeName("$anon"),
              List(),
              Template(
                List(
                  AppliedTypeTree(
                    lazyHelperSelect,
                    List(
                      Ident(matag.tpe.typeSymbol),
                      Ident(atag.tpe.typeSymbol)
                    )
                  )
                ),
                emptyValDef,
                List(
                  DefDef(
                    Modifiers(),
                    nme.CONSTRUCTOR,
                    List(),
                    List(List()),
                    TypeTree(),
                    Block(
                      List(
                        Apply(
                          Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR),
                          List()
                        )
                      ),
                      Literal(Constant(()))
                    )
                  ),
                  ValDef(
                    Modifiers(Flag.OVERRIDE | Flag.LAZY),
                    newTermName("lazyStuff"),
                    AppliedTypeTree(Ident(matag.tpe.typeSymbol), List(TypeTree(atag.tpe))),
                    finalTree
                  )
                )
              )
            )
          ),
          Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())
        ),
        newTermName("lazyStuff")
      )
      //println("block:"+block)
      block
    }
  }

}
