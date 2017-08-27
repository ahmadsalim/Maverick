package semantics

import semantics.domains.abstracting._
import semantics.domains.abstracting.TypeStore.TypeResult
import semantics.domains.common._
import semantics.domains.concrete.TypeOps._
import Product._
import semantics.typing.AbstractTyping
import syntax._

import scalaz.\/
import scalaz.std.list._
import scalaz.std.option._
import scalaz.syntax.traverse._
import scalaz.syntax.either._

// TODO: Convert lub(...flatMap) to map(...lub)
case class AbstractRefinementTypeExecutor(module: Module, initialRefinements: Refinements, precise: Boolean = false) {
  private
  val atyping = AbstractTyping(module)

  private
  val refinements: Refinements = initialRefinements

  private
  val typememoriesops = TypeMemoriesOps(module, refinements)

  import typememoriesops._
  import typestoreops._
  import refinementtypeops._

  private
  type FunMemo = Map[(VarName, List[Type]), (List[VoideableRefinementType], TypeMemories[VoideableRefinementType])]

  private
  def ifFail[A](res: TypeResult[A], typ: A): A = res match {
    case SuccessResult(typ_) => typ_
    case ExceptionalResult(Fail) => typ
    case _ => throw new UnsupportedOperationException(s"ifFail($res, $typ)")
  }

  private
  def safeReconstruct(scrtyp: VoideableRefinementType,
                      cvtys: List[VoideableRefinementType]): VoideableRefinementType = {
    reconstruct(scrtyp, cvtys.map(_.copy(possiblyVoid = false))).head match {
      case SuccessResult(t) => t
      case ExceptionalResult(exres) => assert(false); throw new Exception("safeReconstruct")
    }
  }

  private
  def reconstruct(scrtyp: VoideableRefinementType,
                  cvtys: List[VoideableRefinementType]): Set[TypeResult[VoideableRefinementType]] = {
    val voideableres: Set[TypeResult[VoideableRefinementType]] =
      if (scrtyp.possiblyVoid && cvtys.nonEmpty)
        Set(ExceptionalResult(Error(Set(ReconstructError(scrtyp, cvtys)))))
      else Set()
    val ordres: Set[TypeResult[VoideableRefinementType]] = scrtyp.refinementType match {
      case BaseRefinementType(b) =>
        if (cvtys.isEmpty) Set(SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, BaseRefinementType(b))))
        else Set(ExceptionalResult(Error(Set(ReconstructError(scrtyp, cvtys)))))
      case DataRefinementType(dataname, refinenameo) =>
        val constructors = refinenameo.fold(module.datatypes(dataname).toSet)(refinename => refinements.definitions.apply(refinename).conss.keySet)
        val consres = constructors.foldLeft[Set[TypeResult[Map[ConsName, List[RefinementType]]]]](Set(SuccessResult(Map.empty)))
          { (prevres, cons) =>
            prevres.flatMap[TypeResult[Map[ConsName, List[RefinementType]]], Set[TypeResult[Map[ConsName, List[RefinementType]]]]] {
              case SuccessResult(consmap) =>
                val (_, parameters) = module.constructors(cons)
                val zipped = cvtys.zip(parameters.map(_.typ))
                val posSuc: Set[TypeResult[Map[ConsName, List[RefinementType]]]] =
                  if (cvtys.length == parameters.length &&
                    zipped.forall { case (vrty, ty) => atyping.checkType(vrty.refinementType, ty).contains(true) }) {
                    Set(SuccessResult(consmap.updated(cons, cvtys.map(_.refinementType))))
                  } else Set()
                val posEx: Set[TypeResult[Map[ConsName, List[RefinementType]]]] =
                  if (cvtys.length != parameters.length ||
                    zipped.exists { case (vrty, ty) => atyping.checkType(vrty.refinementType, ty).contains(false) } ||
                    cvtys.exists(_.possiblyVoid)) {
                    Set(ExceptionalResult(Error(Set(ReconstructError(scrtyp, cvtys)))))
                  }
                  else Set()
                posEx ++ posSuc
              case ExceptionalResult(exres) => Set(ExceptionalResult(exres))
            }
        }
        consres.map {
          case SuccessResult(consmap) =>
            val newRn = refinements.newRefinement(dataname)
            val newrnopt = addRefinement(dataname, newRn, consmap)
            newrnopt.fold(SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, DataRefinementType(dataname, None)))) { newrn =>
              SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, DataRefinementType(dataname, Some(newrn))))
            }
          case ExceptionalResult(exres) => ExceptionalResult(exres)
        }
      case ListRefinementType(_) =>
        val cvoideableres =
          if (cvtys.exists(_.possiblyVoid)) Set(ExceptionalResult(Error(Set(ReconstructError(scrtyp, cvtys)))))
          else Set()
        val tylub = Lattice[RefinementType].lubs(cvtys.map(_.refinementType).toSet)
        cvoideableres ++ Set(SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, ListRefinementType(tylub))))
      case SetRefinementType(_) =>
        val cvoideableres =
          if (cvtys.exists(_.possiblyVoid)) Set(ExceptionalResult(Error(Set(ReconstructError(scrtyp, cvtys)))))
          else Set()
        val tylub = Lattice[RefinementType].lubs(cvtys.map(_.refinementType).toSet)
        cvoideableres ++ Set(SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, SetRefinementType(tylub))))
      case MapRefinementType(_, _) =>
        val cvoideableres =
          if (cvtys.exists(_.possiblyVoid)) Set(ExceptionalResult(Error(Set(ReconstructError(scrtyp, cvtys)))))
          else Set()
        val (nktys, nvtys) = cvtys.map(_.refinementType).splitAt(cvtys.length / 2)
        val newKeyType = Lattice[RefinementType].lubs(nktys.toSet)
        val newValType = Lattice[RefinementType].lubs( nvtys.toSet)
        cvoideableres ++ Set(SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, MapRefinementType(newKeyType, newValType))))
      case NoRefinementType =>
        if (scrtyp.possiblyVoid && cvtys.isEmpty) Set(SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, NoRefinementType)))
        else Set()
      case ValueRefinementType =>
        Set(ExceptionalResult(Error(Set(ReconstructError(scrtyp, cvtys)))), SuccessResult(VoideableRefinementType(scrtyp.possiblyVoid, ValueRefinementType)))
    }
    voideableres ++ ordres
  }

  private
  def refineEq(vrty1: VoideableRefinementType, vrty2: VoideableRefinementType): Option[VoideableRefinementType] = {
    val vrtyglb = Lattice[VoideableRefinementType].glb(vrty1, vrty2)
    if (Lattice[VoideableRefinementType].isBot(vrtyglb)) None
    else Some(vrtyglb)
  }

  def refineNeq(vrty1: VoideableRefinementType, vrty2: VoideableRefinementType): Option[(VoideableRefinementType, VoideableRefinementType)] = {
    (vrty1.refinementType, vrty2.refinementType) match {
      case (DataRefinementType(dn1, rno1), DataRefinementType(dn2, rno2)) if dn1 == dn2 =>
        val drglb = Lattice[RefinementType].glb(vrty1.refinementType, vrty2.refinementType)
        if (Lattice[RefinementType].isBot(drglb)) None
        else {
          val drnegglb = refinementtypeops.negate(drglb)
          val vrty1r = vrty1.copy(refinementType = Lattice[RefinementType].glb(vrty1.refinementType, drnegglb))
          val vrty2r = vrty2.copy(refinementType = Lattice[RefinementType].glb(vrty2.refinementType, drnegglb))
          Some((vrty1r, vrty2r))
        }
      case _ => None // Currently there is no way to refine inequality for the rest of the domains
    }
  }

  // Use Set instead of Stream for nicer equality, and easier structural traversal when having alternatives
  def mergePairs(pairs: Set[(Map[VarName, VoideableRefinementType], Map[VarName, VoideableRefinementType])]): Set[Set[Map[VarName, VoideableRefinementType]]] = {
    // TODO Seems to lose the laziness, but I am still unsure how to recover that
    val merged = pairs.toList.traverse[List, Map[VarName, VoideableRefinementType]] { case (env1, env2) =>
      val commonVars = env1.keySet.intersect(env2.keySet)
      val refinedEqCommon = commonVars.toList.foldLeftM[Option, Map[VarName, VoideableRefinementType]](Map.empty[VarName, VoideableRefinementType]) { (commonVarVals, x) =>
        refineEq(env1(x), env2(x)).map { xval => commonVarVals.updated(x, xval) }
      }
      refinedEqCommon.fold[List[Map[VarName, VoideableRefinementType]]](List()) { commonVarVals =>
        List(env1 ++ env2 ++ commonVarVals)
      }
    }
    merged.map(_.toSet).toSet
  }

  def merge(envss: List[Set[Map[VarName, VoideableRefinementType]]]): Set[Set[Map[VarName, VoideableRefinementType]]] = {
    envss.foldLeft[Set[Set[Map[VarName, VoideableRefinementType]]]](Set(Set(Map()))) { (envsset, merged) =>
      envsset.flatMap { envs =>
        val pairsEnvs = envs.flatMap { env => merged.map(menv => (env, menv)) }
        mergePairs(pairsEnvs)
      }
    }
  }

  def matchPattAll(store: TypeStore, scrtyp: RefinementType, spatts: List[StarPatt], construct: RefinementType => RefinementType): Set[(TypeStore, RefinementType, Set[Map[syntax.VarName, VoideableRefinementType]])] = {
    spatts match {
      // No refinements on store and scrutinee possible on empty list pattern on failure, and if succesful we can be more specific about the element type
      case Nil => Set((store, scrtyp, Set()), (store, NoRefinementType, Set(Map())))
      case sp :: sps =>
        sp match {
          case OrdinaryPatt(p) =>
            // If the concrete list happens to be empty, NoRefinementType is the most precise sound abstraction we can give of the elements on failure
            Set((store, NoRefinementType, Set[Map[VarName, VoideableRefinementType]]())) ++
              matchPatt(store, VoideableRefinementType(possiblyVoid = false, scrtyp), p).flatMap { case (refinestore, vrefinescrtyp, envp) =>
                // Use the same scrtyp (and not the refined one) since refinement of individual elements in collection does not affect other elements
                matchPattAll(refinestore, scrtyp, sps, construct).flatMap { case (refinestore2, refinescrtyp2, envps) =>
                  val mergeres = merge(List[Set[Map[VarName, VoideableRefinementType]]](envp, envps))
                  mergeres.map { mergedenvs =>
                    val scrtyplub = Lattice[RefinementType].lub(vrefinescrtyp.refinementType, refinescrtyp2)
                    (refinestore2, scrtyplub, mergedenvs)
                  }
                }
              }
          case ArbitraryPatt(sx) =>
            def bindVar = matchPattAll(setVar(store, sx, VoideableRefinementType(possiblyVoid = false, construct(scrtyp))), scrtyp, sps, construct).map { case (refinestore, _, envp) =>
              // We move the variable that we put in the store (for optimization) to the environment
              // We can use the refined result type since it is independent of the elements matched here
              (dropVars(refinestore, Set(sx)), scrtyp, envp.map(_.updated(sx, getVar(refinestore,sx).get)))
            }
            getVar(store, sx).fold(bindVar) { vsxtyp =>
              val refined = refineEq(VoideableRefinementType(possiblyVoid = false, scrtyp), VoideableRefinementType(possiblyVoid = false, vsxtyp.refinementType))
              // When things are inequal we fail, and there is little refinement we can do in the general case
              // TODO investigate specific refinements for disequality
              val posBind = if (vsxtyp.possiblyVoid) bindVar else Set[(TypeStore, RefinementType, Set[Map[syntax.VarName, VoideableRefinementType]])]()
              posBind ++ Set((store, scrtyp, Set[Map[VarName, VoideableRefinementType]]())) ++
                refined.fold(Set[(TypeStore, RefinementType, Set[Map[VarName, VoideableRefinementType]])]()) { vrteq =>
                // Refine the store given that the patterns were equal
                val refinestore = setVar(store, sx, vrteq)
                matchPattAll(refinestore, scrtyp, sps, construct).map { case (refinestore2, refinescrtyp, envp) =>
                  // We merge refinements of elements
                  val scrtyplub = Lattice[RefinementType].lub(vrteq.refinementType, refinescrtyp)
                  (refinestore2, scrtyplub, envp)
                }
              }
            }
        }
    }
  }

  private
  type MatchPattRes = (TypeStore, VoideableRefinementType, Set[(Map[syntax.VarName, VoideableRefinementType])])

  // TODO Consider merging succesful and failing environments for optimization
  def matchPatt(store: TypeStore, scrvrtyp: VoideableRefinementType, cspatt: Patt): Set[MatchPattRes] = {
    val matchress: Set[MatchPattRes] = cspatt match {
      case BasicPatt(b) =>
        b match {
          case IntLit(_) => scrvrtyp.refinementType match {
            case BaseRefinementType(IntType) | ValueRefinementType =>
              Set((store, scrvrtyp, Set()),
                (store, VoideableRefinementType(scrvrtyp.possiblyVoid, BaseRefinementType(IntType)), Set(Map())))
            case _ => Set((store, scrvrtyp, Set()))
          }
          case StringLit(_) => scrvrtyp.refinementType match {
            case BaseRefinementType(StringType) | ValueRefinementType =>
              Set((store, scrvrtyp, Set()),
                (store, VoideableRefinementType(scrvrtyp.possiblyVoid, BaseRefinementType(StringType)), Set(Map())))
            case _ => Set((store, scrvrtyp, Set()))
          }
        }
      case IgnorePatt => Set((store, scrvrtyp, Set(Map())))
      case VarPatt(name) =>
        def bindVar = Set((store, scrvrtyp, Set(Map(name -> scrvrtyp))))
        getVar(store, name).fold[Set[MatchPattRes]](bindVar) { xvrtyp =>
          val refineeqres = refineEq(scrvrtyp, VoideableRefinementType(possiblyVoid = false, xvrtyp.refinementType))
          val refineneqres = refineNeq(scrvrtyp, VoideableRefinementType(possiblyVoid = false, xvrtyp.refinementType))
          val posBind: Set[MatchPattRes] = if (xvrtyp.possiblyVoid) bindVar else Set()
          posBind ++
            Set[MatchPattRes](refineneqres.fold((store, scrvrtyp, Set[Map[VarName, VoideableRefinementType]]())) { case (nscrvrtyp, nxvrtyp) =>
              (setVar(store, name, nxvrtyp), nscrvrtyp, Set[Map[VarName, VoideableRefinementType]]())
            }) ++
              refineeqres.fold[Set[MatchPattRes]](Set()) { vrteq =>
                Set((setVar(store, name, vrteq), vrteq, Set(Map())))
              }
        }
      case ConstructorPatt(pattconsname, chpatts) =>
        val voidRes: Set[MatchPattRes] =
          if (scrvrtyp.possiblyVoid)
            Set((store, VoideableRefinementType(possiblyVoid = true, NoRefinementType), Set()))
          else Set()
        val dataRes: Set[MatchPattRes] = {
          val (dt, _) = module.constructors(pattconsname)
          val negGenMatch: Set[MatchPattRes] = Set((store, VoideableRefinementType(possiblyVoid = false, scrvrtyp.refinementType), Set()))
          def matchOn(rno: Option[Refinement]): Set[MatchPattRes] = {
            val refinementdef = rno.fold(dataTypeDefToRefinementDef(dt, typememoriesops.datatypes(dt)))(refinements.definitions)
            val failCons = refinementdef.conss - pattconsname
            val failRes: Set[MatchPattRes] =
              if (failCons.isEmpty) Set()
              else {
                val newRn = refinements.newRefinement(dt)
                val nrno = addRefinement(dt, newRn, failCons)
                Set((store, VoideableRefinementType(possiblyVoid = false, DataRefinementType(dt, nrno)), Set()))
              }
            val sucRes: Set[MatchPattRes] = {
              refinementdef.conss.get(pattconsname).fold(Set[MatchPattRes]()) { chvrtys =>
                val chrefres = chpatts.toList.zip(chvrtys).foldLeftM[List, (TypeStore, List[RefinementType], Set[Map[VarName, VoideableRefinementType]])]((store, List.empty, Set(Map[VarName, VoideableRefinementType]()))) {
                  case (st, (chpatt, chrty)) =>
                    val (prevrefinestore, prevrefinechrtys, prevenvs) = st
                    matchPatt(prevrefinestore, VoideableRefinementType(possiblyVoid = false, chrty), chpatt).toList.flatMap {
                      case (refinestore, refinechrty, chenvs) =>
                        val merged = merge(List(prevenvs, chenvs))
                        merged.map { menvss =>
                          (refinestore, prevrefinechrtys :+ refinechrty.refinementType, menvss)
                        }.toList
                    }
                }.toSet
                chrefres.map { case (refinestore, refinechrtys, chenvs) =>
                  val newRn = refinements.newRefinement(dt)
                  val nrno = addRefinement(dt, newRn, Map(pattconsname -> refinechrtys))
                  (refinestore, VoideableRefinementType(possiblyVoid = false, DataRefinementType(dt, nrno)), chenvs)
                }
              }
            }
            failRes ++ sucRes
          }
          scrvrtyp.refinementType match {
            case DataRefinementType(dn, rno) if dn == dt => matchOn(rno)
            case ValueRefinementType => negGenMatch ++ matchOn(None)
            case _ => negGenMatch
          }
        }
        voidRes ++ dataRes
      case LabelledTypedPatt(typ, labelVar, patt) =>
        val posEx: Set[MatchPattRes] =
          if (atyping.checkType(scrvrtyp.refinementType, typ).contains(false))
            Set((store, scrvrtyp, Set()))
          else Set()
        val posSuc: Set[MatchPattRes] = if (atyping.checkType(scrvrtyp.refinementType, typ).contains(true)) {
          val inmatchs = matchPatt(store, scrvrtyp, patt)
          inmatchs.flatMap { case (refinestore, refinescrvrtyp, inmatch) =>
            merge(List(Set(Map(labelVar -> scrvrtyp)), inmatch)).map { menvs => (refinestore, refinescrvrtyp, menvs) }
          }
        } else Set()
        posEx ++ posSuc
      case ListPatt(spatts) =>
        val voidRes: Set[MatchPattRes] =
          if (scrvrtyp.possiblyVoid)
            Set((store, VoideableRefinementType(possiblyVoid = true, NoRefinementType), Set()))
          else Set()
        val listRes: Set[MatchPattRes] = scrvrtyp.refinementType match {
          case ListRefinementType(elementType) =>
            matchPattAll(store, elementType, spatts.toList, ListRefinementType).map {
              case (refinestore, refineelementType, envs) =>
                (refinestore, VoideableRefinementType(possiblyVoid = false, ListRefinementType(refineelementType)), envs)
            }
          case ValueRefinementType => Set[MatchPattRes]((store, scrvrtyp, Set())) ++
            matchPattAll(store, ValueRefinementType, spatts.toList, ListRefinementType).map {
              case (refinestore, refineelementType, envs) =>
                (refinestore, VoideableRefinementType(possiblyVoid = false, ListRefinementType(refineelementType)), envs)
            }
          case _ =>
            Set((store, VoideableRefinementType(possiblyVoid = false, scrvrtyp.refinementType), Set()))
        }
        voidRes ++ listRes
      case SetPatt(spatts) =>
        val voidRes: Set[MatchPattRes] =
          if (scrvrtyp.possiblyVoid)
            Set((store, VoideableRefinementType(possiblyVoid = true, NoRefinementType), Set()))
          else Set()
        val setRes: Set[MatchPattRes] = scrvrtyp.refinementType match {
          case SetRefinementType(elementType) =>
            matchPattAll(store, elementType, spatts.toList, SetRefinementType).map {
              case (refinestore, refineelementType, envs) =>
                (refinestore, VoideableRefinementType(possiblyVoid = false, SetRefinementType(refineelementType)), envs)
            }
          case ValueRefinementType => Set[MatchPattRes]((store, scrvrtyp, Set())) ++
            matchPattAll(store, ValueRefinementType, spatts.toList, SetRefinementType).map {
              case (refinestore, refineelementType, envs) =>
                (refinestore, VoideableRefinementType(possiblyVoid = false, SetRefinementType(refineelementType)), envs)
            }
          case _ =>
            Set((store, VoideableRefinementType(possiblyVoid = false, scrvrtyp.refinementType), Set()))
        }
        voidRes ++ setRes
      case NegationPatt(patt) =>
        matchPatt(store, scrvrtyp, patt).map[MatchPattRes, Set[MatchPattRes]] { case (_, _, envs) =>
          // TODO Consider calculating better negations for refinements of stores and input
          // Drop local refinements since they are invalid
          if (envs.isEmpty) (store, scrvrtyp, Set(Map()))
          else (store, scrvrtyp, Set())
        }
      case DescendantPatt(patt) =>
        def memoFix(store: TypeStore, vrtyp: VoideableRefinementType, memo: Map[VoideableRefinementType, Set[MatchPattRes]]): Set[MatchPattRes] = {
          def go(prevres: Set[MatchPattRes]): Set[MatchPattRes] = {
            val newres = matchPatt(store, vrtyp, patt).flatMap { case (refinestore, refinevrtyp, selfenvs) =>
              val childrenres = children(refinevrtyp)
              childrenres.flatMap { case (_, chrtyps) =>
                chrtyps.flatMap { chrtyp =>
                  val chrtyres = memoFix(refinestore, VoideableRefinementType(possiblyVoid = false, chrtyp), memo.updated(vrtyp, prevres))
                  chrtyres.map { case (nrefinestore, _, cenvss) =>
                    // TODO Think about refinement with descendants (does it require reconstruction?)
                    (nrefinestore, vrtyp, selfenvs.flatMap { senv =>  cenvss.map { cenv => senv ++ cenv } })
                  }
                }
              }
            }
            // TODO Check whether the widening (on the output) here is correct
            if (newres == prevres) newres
            else go(prevres union newres)
          }
          memo.getOrElse(vrtyp, go(Set()))
        }
        memoFix(store, scrvrtyp, Map())
    }
    matchress.groupBy(_._3).toSet[(Set[Map[VarName, VoideableRefinementType]], Set[MatchPattRes])].map { case (envs, matchres) =>
      val allrefinestores = matchres.map(_._1)
      val refinestorelub = Lattice[TypeStore].lubs(allrefinestores)
      val allrefinetyps = matchres.map(_._2)
      val refinetypslub = Lattice[VoideableRefinementType].lubs(allrefinetyps)
      (refinestorelub, refinetypslub, envs)
    }
  }

  def evalVar(store: TypeStore, x: VarName): TypeMemories[VoideableRefinementType] = {
    val unassignedError = Set(TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(UnassignedVarError(x)))), store))
    getVar(store, x).fold(TypeMemories[VoideableRefinementType](unassignedError)) {
      case VoideableRefinementType(possUnassigned, rtyp) =>
        val posErr = if (possUnassigned) unassignedError else Set[TypeMemory[VoideableRefinementType]]()
        TypeMemories(posErr ++
          Set(TypeMemory[VoideableRefinementType](SuccessResult(VoideableRefinementType(possiblyVoid = false, rtyp)), store)))
    }
  }

  private
  def unAlistMems[A](alistMems: TypeMemories[AList[A]]): TypeMemories[List[A]] = {
    TypeMemories(alistMems.memories.map {
      case TypeMemory(res, st) =>
        TypeMemory(res match {
          case SuccessResult(t) => SuccessResult(AList.getList(t))
          case ExceptionalResult(exres) => ExceptionalResult(exres)
        }, st)
    })
  }

  private
  def unflatMems[A](flatMems: TypeMemories[Flat[A]]): TypeMemories[A] = {
    TypeMemories(flatMems.memories.map {
      case TypeMemory(res, st) =>
        TypeMemory(res match {
          case SuccessResult(t) => SuccessResult(Flat.unflat(t))
          case ExceptionalResult(exres) => ExceptionalResult(exres)
        }, st)
    })
  }

  def accessField(vrty: VoideableRefinementType, fieldName: FieldName): Set[TypeResult[VoideableRefinementType]] = {
    val voidRes: Set[TypeResult[VoideableRefinementType]] =
      if (vrty.possiblyVoid)
        Set(ExceptionalResult(Error(Set(FieldError(VoideableRefinementType(possiblyVoid = true, NoRefinementType), fieldName)))))
      else Set()
    val typRes: Set[TypeResult[VoideableRefinementType]] = vrty.refinementType match {
      case DataRefinementType(dn, rno) =>
        val refinementdef = rno.fold(dataTypeDefToRefinementDef(dn, typememoriesops.datatypes(dn)))(refinements.definitions)
        val fieldRes: Set[TypeResult[VoideableRefinementType]] = refinementdef.conss.map { case (cons, chrtys) =>
          val (_, pars) = module.constructors(cons)
          val index = pars.indexWhere(_.name == fieldName)
          if (index < 0) { ExceptionalResult(Error(Set(FieldError(vrty, fieldName)))) }
          else SuccessResult[VoideableRefinementType](VoideableRefinementType(possiblyVoid = false, chrtys(index)))
        }.toSet
        fieldRes
      case ValueRefinementType =>
        val fieldTypUB = Lattice[Type].lubs(module.constructors.values.toSet[(TypeName, List[Parameter])].map(_._2)
                                              .flatMap(pars => pars.find(_.name == fieldName).map(_.typ)))
          // Take lub of all possible field types
        Set(ExceptionalResult(Error(Set(FieldError(vrty, fieldName)))),
          SuccessResult(VoideableRefinementType(possiblyVoid = false, typeToRefinement(fieldTypUB))))
      case _ => Set(ExceptionalResult(Error(Set(FieldError(vrty, fieldName)))))
    }
    voidRes ++ typRes
  }

  def evalFieldAccess(localVars: Map[VarName, Type], store: TypeStore, target: Expr, fieldName: FieldName, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val targetmems = evalLocal(localVars, store, target, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(targetmems.memories.map { case TypeMemory(targetres, store_) =>
      targetres match {
        case SuccessResult(tv) =>
          Lattice[TypeMemories[VoideableRefinementType]].lubs(accessField(tv, fieldName).map(res =>
            TypeMemories[VoideableRefinementType](Set(TypeMemory(res, store_)))))
        case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(targetres, store_)))
      }
    })
  }

  def evalUnaryOp(op: OpName, vrtyp: VoideableRefinementType): Set[TypeResult[VoideableRefinementType]] = {
    if (Lattice[VoideableRefinementType].isBot(vrtyp)) Set()
    else {
      val errRes: Set[TypeResult[VoideableRefinementType]] = Set(ExceptionalResult(Error(Set(InvalidOperationError(op, List(vrtyp))))))
      val voidRes = if (vrtyp.possiblyVoid) errRes else Set[TypeResult[VoideableRefinementType]]()
      val rtyp = vrtyp.refinementType
      val typRes = (op, rtyp) match {
        case ("-", BaseRefinementType(IntType) | ValueRefinementType) =>
          (if (rtyp == ValueRefinementType) errRes else Set()) ++
            Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
        case ("!", DataRefinementType("Bool", rno)) =>
          rno.fold(Set[TypeResult[VoideableRefinementType]](SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", None))))) { rn =>
            refinements.definitions(rn).conss.toSet[(ConsName, List[RefinementType])].flatMap {
              case ("true", List()) =>
                val newRn = refinements.newRefinement("Bool")
                val newrhs = Map("false" -> List())
                val nrno = addRefinement("Bool", newRn, newrhs)
                Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", nrno))))
              case ("false", List()) =>
                val newRn = refinements.newRefinement("Bool")
                val newrhs = Map("true" -> List())
                val nrno = addRefinement("Bool", newRn, newrhs)
                Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", nrno))))
              case _ => throw NonNormalFormMemories
            }
          }
        case ("!", ValueRefinementType) =>
          errRes ++ Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", None))))
        case _ => errRes
      }
      voidRes ++ typRes
    }
  }

  def evalUnary(localVars: Map[VarName, Type], store: TypeStore, op: OpName, operand: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val mems = evalLocal(localVars, store, operand, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(mems.memories.map { case TypeMemory(res, store_) =>
        res match {
          case SuccessResult(vl) =>
            Lattice[TypeMemories[VoideableRefinementType]].lubs(evalUnaryOp(op, vl).map{ ures => TypeMemories[VoideableRefinementType](Set(TypeMemory(ures, store_))) })
          case ExceptionalResult(exres) => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_)))
        }
    })
  }


  def evalBinaryOp(lhvrtyp: VoideableRefinementType, op: OpName, rhvrtyp: VoideableRefinementType): Set[TypeResult[VoideableRefinementType]] = {
    val invOp = ExceptionalResult(Error(Set(InvalidOperationError(op, List(lhvrtyp, rhvrtyp)))))

    def onNEq(boolcons : ConsName, lhvrtyp: VoideableRefinementType, rhvrtyp: VoideableRefinementType) = {
      refineEq(lhvrtyp, rhvrtyp).fold[Set[TypeResult[VoideableRefinementType]]] {
        val newRn = refinements.newRefinement("Bool")
        val newrhs = Map(boolcons -> List[RefinementType]())
        val nrno = addRefinement("Bool", newRn, newrhs)
        Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", nrno))))
      } { _ => Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", None)))) }
    }


    def boolAnd(lrno: Option[Refinement], rnro: Option[Refinement]): Set[TypeResult[VoideableRefinementType]] = {
      val lrefinedef = lrno.fold(dataTypeDefToRefinementDef("Bool", typememoriesops.datatypes("Bool")))(refinements.definitions)
      lrefinedef.conss.keySet.flatMap {
        case "true" =>
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", rnro))))
        case "false" =>
          val newRn = refinements.newRefinement("bool")
          val newrhs = Map("false" -> List[RefinementType]())
          val newrno = addRefinement("Bool", newRn, newrhs)
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", newrno))))
        case _ => throw NonNormalFormMemories
      }
    }

    def boolOr(lrno: Option[Refinement], rnro: Option[Refinement]): Set[TypeResult[VoideableRefinementType]] =  {
      val lrefinedef = lrno.fold(dataTypeDefToRefinementDef("Bool", typememoriesops.datatypes("Bool")))(refinements.definitions)
      lrefinedef.conss.keySet.flatMap {
        case "false" =>
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", rnro))))
        case "true" =>
          val newRn = refinements.newRefinement("bool")
          val newrhs = Map("true" -> List[RefinementType]())
          val newrno = addRefinement("Bool", newRn, newrhs)
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType("Bool", newrno))))
        case _ => throw NonNormalFormMemories
      }
    }

    if (Set(lhvrtyp, rhvrtyp).exists(Lattice[VoideableRefinementType].isBot)) Set()
    else (lhvrtyp.refinementType, op, rhvrtyp.refinementType) match {
      case (_, "==", _) => onNEq("false", lhvrtyp, rhvrtyp)
      case (_, "!=", _) => onNEq("true", lhvrtyp, rhvrtyp)
      case (_, "in", ListRefinementType(invrtyp)) =>
        (if (rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          onNEq("false", lhvrtyp, VoideableRefinementType(possiblyVoid = false, invrtyp))
      case (_, "in", SetRefinementType(invrtyp)) =>
        (if (rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          onNEq("false", lhvrtyp, VoideableRefinementType(possiblyVoid = false, invrtyp))
      case (_, "in", MapRefinementType(keyvrtyp, _)) =>
        (if (rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          onNEq("false", lhvrtyp, VoideableRefinementType(possiblyVoid = false, keyvrtyp))
      case (_, "in", ValueRefinementType) =>
        Set(invOp) ++ onNEq("false", lhvrtyp, VoideableRefinementType(possiblyVoid = false, ValueRefinementType))
      case (_, "notin", _) => evalBinaryOp(lhvrtyp, "in", rhvrtyp).flatMap {
        case SuccessResult(ty) => evalUnaryOp("!", ty)
        case ExceptionalResult(exres) =>
          Set[TypeResult[VoideableRefinementType]](ExceptionalResult(exres))
      }
      case (DataRefinementType("Bool", lrno), "&&", DataRefinementType("Bool", rnro)) =>
        (if (rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++ boolAnd(lrno, rnro)
      case (ValueRefinementType, "&&", DataRefinementType("Bool", rnro)) =>
        Set(invOp) ++ boolAnd(None, rnro)
      case (DataRefinementType("Bool", lrno), "&&", ValueRefinementType) =>
        Set(invOp) ++ boolAnd(lrno, None)
      case (ValueRefinementType, "&&", ValueRefinementType) =>
        Set(invOp) ++ boolAnd(None, None)
      case (ValueRefinementType, "||", DataRefinementType("Bool", rnro)) =>
        (if (rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++ boolOr(None, rnro)
      case (DataRefinementType("Bool", lrno), "||", ValueRefinementType) =>
        Set(invOp) ++ boolOr(lrno, None)
      case (ValueRefinementType, "||", ValueRefinementType) =>
        Set(invOp) ++ boolOr(None, None)
      case (ListRefinementType(rty1), "+", ListRefinementType(rty2)) =>
        val tylub = Lattice[RefinementType].lub(rty1, rty2)
        Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, ListRefinementType(tylub))))
      case (ListRefinementType(_), "+", ValueRefinementType) | (ValueRefinementType, "+", ListRefinementType(_)) =>
        Set(invOp) ++ Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, ListRefinementType(ValueRefinementType))))
      case (SetRefinementType(rty1), "+", SetRefinementType(rty2)) =>
        val tylub = Lattice[RefinementType].lub(rty1, rty2)
        Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, SetRefinementType(tylub))))
      case (SetRefinementType(_), "+", ValueRefinementType) | (ValueRefinementType, "+",SetRefinementType(_)) =>
        Set(invOp) ++ Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, SetRefinementType(ValueRefinementType))))
      case (MapRefinementType(krty1,vrty1), "+", MapRefinementType(krty2, vrty2)) =>
        val krtylub = Lattice[RefinementType].lub(krty1, krty2)
        val vrtylub = Lattice[RefinementType].lub(vrty1, vrty2)
        Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, MapRefinementType(krtylub, vrtylub))))
      case (MapRefinementType(_,_), "+", ValueRefinementType) |
           (ValueRefinementType, "+", MapRefinementType(_,_)) =>
        Set(invOp) ++ Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, MapRefinementType(ValueRefinementType, ValueRefinementType))))
      case (BaseRefinementType(StringType), "+", BaseRefinementType(StringType)) =>
        (if (lhvrtyp.possiblyVoid || rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(StringType))))
      case (BaseRefinementType(IntType), "+", BaseRefinementType(IntType)) =>
        (if (lhvrtyp.possiblyVoid || rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (BaseRefinementType(StringType), "+", ValueRefinementType) | (ValueRefinementType, "+", BaseRefinementType(StringType)) =>
        Set(invOp, SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(StringType))))
      case (BaseRefinementType(IntType), "+", ValueRefinementType) | (ValueRefinementType, "+", BaseRefinementType(IntType)) =>
        Set(invOp, SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (ValueRefinementType, "+", ValueRefinementType) =>
        Set(invOp,
          SuccessResult(VoideableRefinementType(possiblyVoid = false, ValueRefinementType)))
      case (BaseRefinementType(IntType), "-", BaseRefinementType(IntType)) =>
        (if (lhvrtyp.possiblyVoid || rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (ValueRefinementType | BaseRefinementType(IntType), "-",  ValueRefinementType | BaseRefinementType(IntType)) =>
        Set(invOp, SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (BaseRefinementType(IntType), "*", BaseRefinementType(IntType)) =>
        (if (lhvrtyp.possiblyVoid || rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (ValueRefinementType | BaseRefinementType(IntType), "*", ValueRefinementType | BaseRefinementType(IntType)) =>
        Set(invOp, SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (BaseRefinementType(IntType), "/", BaseRefinementType(IntType)) =>
        (if (lhvrtyp.possiblyVoid || rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          Set(ExceptionalResult(Throw(VoideableRefinementType(possiblyVoid = false, DataRefinementType("DivByZero", None)))),
            SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (ValueRefinementType | BaseRefinementType(IntType), "/", ValueRefinementType | BaseRefinementType(IntType)) =>
        Set(invOp,
          ExceptionalResult(Throw(VoideableRefinementType(possiblyVoid = false, DataRefinementType("DivByZero", None)))),
          SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (BaseRefinementType(IntType), "%", BaseRefinementType(IntType)) =>
        (if (lhvrtyp.possiblyVoid || rhvrtyp.possiblyVoid) Set(invOp) else Set()) ++
          Set(ExceptionalResult(Throw(VoideableRefinementType(possiblyVoid = false, DataRefinementType("ModNonPos", None)))),
            SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case (ValueRefinementType | BaseRefinementType(IntType), "%", ValueRefinementType | BaseRefinementType(IntType)) =>
        Set(invOp,
          ExceptionalResult(Throw(VoideableRefinementType(possiblyVoid = false, DataRefinementType("ModNonPos", None)))),
          SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))))
      case _ => Set(invOp)
    }
  }

  def evalBinary(localVars: Map[VarName, Type], store: TypeStore, left: Expr, op: OpName, right: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val leftmems = evalLocal(localVars, store, left, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(leftmems.memories.map { case TypeMemory(lhres, store__) =>
        lhres match {
          case SuccessResult(lhval) =>
            val rightmems = evalLocal(localVars, store__, right, funMemo)
            Lattice[TypeMemories[VoideableRefinementType]].lubs(rightmems.memories.map { case TypeMemory(rhres, store_) =>
                rhres match {
                  case SuccessResult(rhval) =>
                    Lattice[TypeMemories[VoideableRefinementType]].lubs(evalBinaryOp(lhval, op, rhval).map { res => TypeMemories[VoideableRefinementType](Set(TypeMemory(res, store_))) })
                  case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(rhres, store_)))
                }
            })
          case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(lhres, store__)))
        }
    })
  }

  def evalConstructor(localVars: Map[VarName, Type], store: TypeStore, consname: ConsName, args: Seq[Expr], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val argmems = evalLocalAll(localVars, store, args, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(argmems.memories.map {
      case TypeMemory(argres, store_) =>
        argres match {
          case SuccessResult(vrtys) =>
            val (tyname, parameters) = module.constructors(consname)
            val tysparszipped = vrtys.zip(parameters.map(_.typ))
            val posEx: Set[TypeMemory[VoideableRefinementType]] =
              if (vrtys.length != parameters.length ||
                tysparszipped.exists { case (vrty, party) => vrty.possiblyVoid || atyping.checkType(vrty.refinementType, party).contains(false) })
                Set(TypeMemory(ExceptionalResult(Error(Set(SignatureMismatch(consname, vrtys, parameters.map(_.typ))))), store_))
              else Set()
            val posSuc: Set[TypeMemory[VoideableRefinementType]] =
              if (vrtys.length == parameters.length &&
                  tysparszipped.forall { case (vrty, party) => atyping.checkType(vrty.refinementType, party).contains(true) }) {
                val newRn = refinements.newRefinement(tyname)
                val newrhs = Map(consname -> vrtys.map(_.refinementType))
                val nrno = addRefinement(tyname, newRn, newrhs)
                Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType(tyname, nrno))), store_))
              } else Set()
            TypeMemories(posEx ++ posSuc)
          case ExceptionalResult(exres) =>
            TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_)))
        }
    })
  }


  private def
  evalCollection(vrtys: List[VoideableRefinementType], coll: RefinementType => RefinementType, store: TypeStore): Set[TypeMemory[VoideableRefinementType]] = {
    val posEx: Set[TypeMemory[VoideableRefinementType]] =
      if (vrtys.exists(_.possiblyVoid)) Set(TypeMemory(ExceptionalResult(Error(Set(OtherError))), store))
      else Set()
    val posSuc: Set[TypeMemory[VoideableRefinementType]] = {
      val vrtyslub = Lattice[RefinementType].lubs(vrtys.map(_.refinementType).toSet[RefinementType])
      Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = false, coll(vrtyslub))), store))
    }
    posEx ++ posSuc
  }

  def evalList(localVars: Map[VarName, Type], store: TypeStore, elements: Seq[Expr], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val elmems = evalLocalAll(localVars, store, elements, funMemo)
    TypeMemories(elmems.memories.flatMap[TypeMemory[VoideableRefinementType], Set[TypeMemory[VoideableRefinementType]]] { case TypeMemory(res, store_) =>
      res match {
        case SuccessResult(vrtys) =>
          evalCollection(vrtys, ListRefinementType, store_)
        case ExceptionalResult(exres) => Set(TypeMemory(ExceptionalResult(exres), store_))
      }
    })
  }


  def evalSet(localVars: Map[VarName, Type], store: TypeStore, elements: Seq[Expr], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val elmems = evalLocalAll(localVars, store, elements, funMemo)
    TypeMemories(elmems.memories.flatMap[TypeMemory[VoideableRefinementType], Set[TypeMemory[VoideableRefinementType]]] { case TypeMemory(res, store_) =>
      res match {
        case SuccessResult(vrtys) =>
          evalCollection(vrtys, SetRefinementType, store_)
        case ExceptionalResult(exres) => Set(TypeMemory(ExceptionalResult(exres), store_))
      }
    })
  }

  def evalMap(localVars: Map[VarName, Type], store: TypeStore, keyvalues: Seq[(Expr, Expr)], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val keyexprs = keyvalues.map(_._1)
    val valexprs = keyvalues.map(_._2)
    val keymems = evalLocalAll(localVars, store, keyexprs, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(keymems.memories.map[TypeMemories[VoideableRefinementType], Set[TypeMemories[VoideableRefinementType]]] {
      case TypeMemory(keyres, store__) =>
        keyres match {
          case SuccessResult(keyvrtys) =>
            val valmems = evalLocalAll(localVars, store__, valexprs, funMemo)
            TypeMemories[VoideableRefinementType](valmems.memories.flatMap { case TypeMemory(valres, store_) =>
              valres match {
                case SuccessResult(valvrtys) =>
                  val posEx: Set[TypeMemory[VoideableRefinementType]] =
                    if (keyvrtys.exists(_.possiblyVoid) || valvrtys.exists(_.possiblyVoid))
                      Set(TypeMemory(ExceptionalResult(Error(Set(OtherError))), store))
                    else Set()
                  val posSuc: Set[TypeMemory[VoideableRefinementType]] = {
                    val keytylub = Lattice[RefinementType].lubs(keyvrtys.map(_.refinementType).toSet[RefinementType])
                    val valtylub = Lattice[RefinementType].lubs(valvrtys.map(_.refinementType).toSet[RefinementType])
                    Set(TypeMemory(SuccessResult(
                      VoideableRefinementType(possiblyVoid = false, MapRefinementType(keytylub, valtylub))), store_))
                  }
                  posEx ++ posSuc
                case ExceptionalResult(exres) =>
                  Set(TypeMemory[VoideableRefinementType](ExceptionalResult(exres), store_))
              }
            })
          case ExceptionalResult(exres) => TypeMemories(Set(TypeMemory(ExceptionalResult(exres), store__)))
        }
    })
  }

  def evalMapLookup(localVars: Map[VarName, Type], store: TypeStore, emap: Expr, ekey: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val mapmems = evalLocal(localVars, store, emap, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(mapmems.memories.flatMap { case TypeMemory(mapres, store__) =>
      mapres match {
        case SuccessResult(mapty) =>
          val errRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(TypeError(mapty, MapType(ValueType, ValueType))))), store__)
          def lookupOnMap(keyType: RefinementType, valueType: RefinementType): Set[TypeMemories[VoideableRefinementType]] = {
            val keymems = evalLocal(localVars, store__, ekey, funMemo)
            keymems.memories.flatMap[TypeMemories[VoideableRefinementType], Set[TypeMemories[VoideableRefinementType]]] { case TypeMemory(keyres, store_) =>
                keyres match {
                  case SuccessResult(actualVRKeyType) =>
                    val keyTypeEqO = refineEq(actualVRKeyType, VoideableRefinementType(possiblyVoid = false, keyType))
                    val posEx: Set[TypeMemories[VoideableRefinementType]] = keyTypeEqO.fold(
                      Set(TypeMemories[VoideableRefinementType](
                        Set(TypeMemory(ExceptionalResult(Throw(VoideableRefinementType(possiblyVoid = false,
                          DataRefinementType("NoKey", None)))), store_)))))(_ => Set[TypeMemories[VoideableRefinementType]]())
                    val posSuc: Set[TypeMemories[VoideableRefinementType]] = keyTypeEqO.fold(Set[TypeMemories[VoideableRefinementType]]()) { _ =>
                      Set(TypeMemories(Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = false, valueType)), store_))))
                    }
                    posEx ++ posSuc
                  case ExceptionalResult(exres) =>
                    Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
                }
            }
          }
          val voidRes: Set[TypeMemories[VoideableRefinementType]] = if (mapty.possiblyVoid) Set(TypeMemories(Set(errRes))) else Set()
          val tyRes: Set[TypeMemories[VoideableRefinementType]] = mapty.refinementType match {
            case MapRefinementType(keyType, valueType) => lookupOnMap(keyType, valueType)
            case ValueRefinementType => Set(TypeMemories[VoideableRefinementType](Set(errRes))) ++ lookupOnMap(ValueRefinementType, ValueRefinementType)
            case _ =>
              Set(TypeMemories[VoideableRefinementType](Set(errRes)))
          }
          voidRes ++ tyRes
        case ExceptionalResult(exres) =>
          Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
      }
    })
  }

  def evalMapUpdate(localVars: Map[VarName, Type], store: TypeStore, emap: Expr, ekey: Expr, evl: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val mapmems = evalLocal(localVars, store, emap, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(mapmems.memories.flatMap { case TypeMemory(mapres, store___) =>
      mapres match {
        case SuccessResult(mapty) =>
          def updateOnMap(keyType: RefinementType, valueType: RefinementType): Set[TypeMemories[VoideableRefinementType]] = {
            val keymems = evalLocal(localVars, store___, ekey, funMemo)
            keymems.memories.flatMap { case TypeMemory(keyres, store__) =>
              keyres match {
                case SuccessResult(keyvrt) =>
                  val keyVoidRes: Set[TypeMemories[VoideableRefinementType]] =
                    if (keyvrt.possiblyVoid)
                      Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Error(Set(OtherError))), store__))))
                    else Set()
                  val keyTypeRes: Set[TypeMemories[VoideableRefinementType]] = {
                    val valmems = evalLocal(localVars, store__, evl, funMemo)
                    valmems.memories.flatMap { case TypeMemory(valres, store_) =>
                      valres match {
                        case SuccessResult(valvrt) =>
                          val valVoidRes: Set[TypeMemories[VoideableRefinementType]] =
                            if (valvrt.possiblyVoid)
                              Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Error(Set(OtherError))), store__))))
                            else Set()
                          val valTypeRes: Set[TypeMemories[VoideableRefinementType]] = {
                            val keylub = Lattice[RefinementType].lub(keyvrt.refinementType, keyType)
                            val vallub = Lattice[RefinementType].lub(valvrt.refinementType, valueType)
                            Set(TypeMemories[VoideableRefinementType](
                              Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = false, MapRefinementType(keylub, vallub))), store_))))
                          }
                          valVoidRes ++ valTypeRes
                        case ExceptionalResult(exres) =>
                          Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
                      }
                    }
                  }
                  keyVoidRes ++ keyTypeRes
                case ExceptionalResult(exres) =>
                  Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
              }
            }
          }
          val errRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(TypeError(mapty, MapType(ValueType, ValueType))))), store___)
          val voidRes: Set[TypeMemories[VoideableRefinementType]] = if (mapty.possiblyVoid) Set(TypeMemories(Set(errRes))) else Set()
          val typRes: Set[TypeMemories[VoideableRefinementType]] = mapty.refinementType match {
            case MapRefinementType(keyType, valueType) => updateOnMap(keyType, valueType)
            case  ValueRefinementType => Set(TypeMemories[VoideableRefinementType](Set(errRes))) ++ updateOnMap(ValueRefinementType, ValueRefinementType)
            case _ => Set(TypeMemories(Set(errRes)))
          }
          voidRes ++ typRes
        case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store___))))
      }
    })
  }

  def evalFunCall(localVars: Map[VarName, Type], store: TypeStore, functionName: VarName, args: Seq[Expr], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    def memoFix(argtys: List[VoideableRefinementType], store: TypeStore): TypeMemories[VoideableRefinementType] = {
      def go(argtys: List[VoideableRefinementType], prevRes: TypeMemories[VoideableRefinementType], reccount: Int): TypeMemories[VoideableRefinementType] = {
        val newFunMemo: FunMemo = funMemo.updated(functionName -> argtys.map(at => atyping.inferType(at.refinementType)), (argtys, prevRes))
        val newRes = {
          val (funresty, funpars, funbody) = module.funs(functionName)
          val argpartyps = argtys.zip(funpars.map(_.typ))
          val errRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(SignatureMismatch(functionName, argtys, funpars.map(_.typ))))), store)
          val posEx: TypeMemories[VoideableRefinementType] =
            if (argtys.length != funpars.length ||
                  argpartyps.exists { case (avrty, party) => atyping.checkType(avrty.refinementType, party).contains(false) })
              TypeMemories[VoideableRefinementType](Set(errRes))
            else Lattice[TypeMemories[VoideableRefinementType]].bot
          val posSuc: TypeMemories[VoideableRefinementType] = {
            if (argtys.length == funpars.length &&
                argpartyps.forall { case (avrty, party) => atyping.checkType(avrty.refinementType, party).contains(true) }) {
              val callRes: TypeMemories[VoideableRefinementType] = {
                val callstore = TypeStoreV(module.globalVars.map { case (x, _) => x -> getVar(store, x).get } ++
                  funpars.map(_.name).zip(argtys).toMap)
                val resmems: TypeMemories[VoideableRefinementType] = funbody match {
                  case ExprFunBody(exprfunbody) =>
                    evalLocal(funpars.map(par => par.name -> par.typ).toMap, callstore, exprfunbody, newFunMemo)
                  case PrimitiveFunBody =>
                    functionName match {
                      case "delete" =>
                        val mapvrty = callstore.vals("emap")
                        val keyvrty = callstore.vals("ekey")
                        val errRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(OtherError))), callstore)
                        val voidRes = if (mapvrty.possiblyVoid || keyvrty.possiblyVoid) Set(errRes) else Set[TypeMemory[VoideableRefinementType]]()
                        val typRes = {
                          mapvrty.refinementType match {
                            case MapRefinementType(kty, vty) =>
                              Set(TypeMemory[VoideableRefinementType](SuccessResult(VoideableRefinementType(possiblyVoid = false, MapRefinementType(kty, vty))), callstore))
                            case _ => Set(errRes)
                          }
                        }
                        TypeMemories(voidRes ++ typRes)
                      case "toString" =>
                        val _ = callstore.vals("earg")
                        TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(StringType))), callstore)))
                      case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Error(Set(OtherError))), callstore)))
                    }
                }
                Lattice[TypeMemories[VoideableRefinementType]].lubs(resmems.memories.map { case TypeMemory(res, resstore) =>
                  val store_ = joinStores(store, TypeStoreV(module.globalVars.map { case (x, _) => x -> getVar(resstore, x).get }))

                  def funcallsuccess(resvrtyp: VoideableRefinementType): TypeMemories[VoideableRefinementType] = {
                    val errRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(TypeError(resvrtyp, funresty)))), store_)
                    val posEx =
                      if (atyping.checkType(resvrtyp.refinementType, funresty).contains(false)) TypeMemories[VoideableRefinementType](Set(errRes))
                      else Lattice[TypeMemories[VoideableRefinementType]].bot
                    val posSuc =
                      if (atyping.checkType(resvrtyp.refinementType, funresty).contains(true)) {
                        TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(resvrtyp), store_)))
                      } else Lattice[TypeMemories[VoideableRefinementType]].bot
                    Lattice[TypeMemories[VoideableRefinementType]].lub(posEx, posSuc)
                  }

                  res match {
                    case SuccessResult(restyp) => funcallsuccess(restyp)
                    case ExceptionalResult(exres) =>
                      exres match {
                        case Return(restyp) => funcallsuccess(restyp)
                        case Break | Continue | Fail =>
                          TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Error(Set(EscapedControlOperator))), store_)))
                        case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_)))
                      }
                  }
                })
              }
              callRes
            }
            else Lattice[TypeMemories[VoideableRefinementType]].bot
          }
          Lattice[TypeMemories[VoideableRefinementType]].lub(posEx, posSuc)
        }
        if (Lattice[TypeMemories[VoideableRefinementType]].<=(newRes, prevRes)) newRes
        else {
          val widened = Lattice[TypeMemories[VoideableRefinementType]].widen(prevRes, newRes)
          go(argtys, widened, reccount = reccount + 1)
        }
      }
      /*
        A memoization strategy must be chose such that it satifies two conditions:
        1: The result is sound
        2: The procedure terminates

        In order to satisfy (1) we must ensure that the resulting output on recursion is always larger than the result from the provided
         input, and to satisfy (2) we must choose a way to conflate the traces based on input.

         Conflating traces of input can happen according to the following strategies:
         S1: Conflate all recursions to the same syntactic judgement
         S2: Conflate recursions to the same syntactic judgement according to some partitioning
         S3: Conflate recursions to the same or larger previous input (works if the input domain is finite)

         S1 is too unprecise in practice, so S2-S4 are preferable.

         In both the cases S1 and S2, we need to widen the current input with the closest previous input to the same judgment in order to get a sound result (otherwise
         the recursion is potentially not monotone).

         If the input domain is not finite, one could also do a further abstraction to the input to a new finite domain and use strategy S3, but this might also lose precision.
       */
      funMemo.get(functionName -> argtys.map(at => atyping.inferType(at.refinementType))).fold(go(argtys, Lattice[TypeMemories[VoideableRefinementType]].bot, reccount = 0)) { case (prevargtys, prevres) =>
        val paapairs = prevargtys.zip(argtys)
        if (paapairs.forall { case (praty, aty) => Lattice[VoideableRefinementType].<=(aty, praty) }) prevres
        else {
          // Widen current input with previous input (strategy S2)
          val newargtys = paapairs.foldLeft(List[VoideableRefinementType]()) { (prevargtys, paap) =>
            val (praty, aty) = paap
            val paapwid = Lattice[VoideableRefinementType].widen(praty, aty)
            prevargtys :+ paapwid
          }
          go(newargtys, Lattice[TypeMemories[VoideableRefinementType]].bot, reccount = 0)
        }
      }
    }
    val argmems = evalLocalAll(localVars, store, args, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(argmems.memories.map { case TypeMemory(argres, store__) =>
      argres match {
        case SuccessResult(argtys) => memoFix(argtys, store__)
        case ExceptionalResult(exres) => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__)))
      }
    })
  }

  def evalReturn(localVars: Map[VarName, Type], store: TypeStore, evl: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val valmems = evalLocal(localVars, store, evl, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(valmems.memories.flatMap { case TypeMemory(valres, store_) =>
      valres match {
        case SuccessResult(valty) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Return(valty)), store_))))
        case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
      }
    })
  }

  def evalAssignable(localVars: Map[VarName, Type], store: TypeStore, assgn: Assignable, funMemo: FunMemo): TypeMemories[DataPath[VoideableRefinementType]] = {
    assgn match {
      case VarAssgn(name) => TypeMemories(Set(TypeMemory(SuccessResult(DataPath(name, List())),store)))
      case FieldAccAssgn(target, fieldName) =>
        val targetmems = evalAssignable(localVars, store, target, funMemo)
        val flatmems = Lattice[TypeMemories[Flat[DataPath[VoideableRefinementType]]]].lubs(
          targetmems.memories.flatMap[TypeMemories[Flat[DataPath[VoideableRefinementType]]], Set[TypeMemories[Flat[DataPath[VoideableRefinementType]]]]] {
          case TypeMemory(targetres, store_) =>
            targetres match {
              case SuccessResult(DataPath(vn, accessPaths)) =>
                Set(TypeMemories(Set(TypeMemory(SuccessResult(FlatValue(DataPath(vn, accessPaths :+ FieldAccessPath(fieldName)))), store_))))
              case ExceptionalResult(exres) =>
                Set(TypeMemories[Flat[DataPath[VoideableRefinementType]]](Set(TypeMemory(ExceptionalResult(exres), store_))))
            }
        })
        unflatMems(flatmems)
      case MapUpdAssgn(target, ekey) =>
        val targetmems = evalAssignable(localVars, store, target, funMemo)
        val flatmems = Lattice[TypeMemories[Flat[DataPath[VoideableRefinementType]]]].lubs(
          targetmems.memories.flatMap[TypeMemories[Flat[DataPath[VoideableRefinementType]]], Set[TypeMemories[Flat[DataPath[VoideableRefinementType]]]]] {
          case TypeMemory(targetres, store__) =>
            targetres match {
              case SuccessResult(DataPath(vn, accessPaths)) =>
                val keymems = evalLocal(localVars, store__, ekey, funMemo)
                Set(TypeMemories(keymems.memories.map { case TypeMemory(keyres, store_) =>
                  keyres match {
                    case SuccessResult(keyt) =>
                      TypeMemory[Flat[DataPath[VoideableRefinementType]]](SuccessResult(FlatValue(DataPath(vn, accessPaths :+ MapAccessPath(keyt)))), store_)
                    case ExceptionalResult(exres) => TypeMemory[Flat[DataPath[VoideableRefinementType]]](ExceptionalResult(exres), store_)
                  }
                }))
              case ExceptionalResult(exres) =>
                Set(TypeMemories[Flat[DataPath[VoideableRefinementType]]](Set(TypeMemory(ExceptionalResult(exres), store__))))
            }
        })
        unflatMems(flatmems)
    }
  }

  def updatePath(rotyp: RefinementType, paths: List[AccessPath[VoideableRefinementType]], vrttyp: VoideableRefinementType): Set[TypeResult[VoideableRefinementType]] = paths match {
    case Nil => Set(SuccessResult(vrttyp))
    case path :: rpaths =>
      path match {
        case MapAccessPath(vrktyp) =>
          def updateOnMap(keyType: RefinementType, valueType: RefinementType): Set[TypeResult[VoideableRefinementType]] = {
            if (rpaths.isEmpty) {
              val keytlub = Lattice[RefinementType].lub(keyType, vrktyp.refinementType)
              val vtlub = Lattice[RefinementType].lub(valueType, vrttyp.refinementType)
              Set[TypeResult[VoideableRefinementType]](
                SuccessResult(VoideableRefinementType(possiblyVoid = false, MapRefinementType(keytlub, vtlub))))
            } else {
              val exRes: Set[TypeResult[VoideableRefinementType]] =
                Set(ExceptionalResult(Throw(VoideableRefinementType(possiblyVoid = false, DataRefinementType("NoKey", None)))))
              val keyeqres = refineEq(vrktyp, VoideableRefinementType(possiblyVoid = false, keyType))
              keyeqres.fold(exRes) { _ =>
                exRes ++ updatePath(valueType, rpaths, vrttyp).flatMap {
                  case SuccessResult(nvaltyp) =>
                    // We only support weak updates on maps
                    val valtylub =
                      Lattice[VoideableRefinementType].lub(VoideableRefinementType(possiblyVoid = false, valueType), nvaltyp)
                    Set[TypeResult[VoideableRefinementType]](
                      SuccessResult(VoideableRefinementType(possiblyVoid = false, MapRefinementType(keyType, valtylub.refinementType))))
                  case ExceptionalResult(exres) =>
                    Set[TypeResult[VoideableRefinementType]](ExceptionalResult(exres))
                }
              }
            }
          }
          val exRes: TypeResult[VoideableRefinementType] =
            ExceptionalResult(Error(Set(TypeError(rotyp, MapType(atyping.inferType(vrktyp.refinementType), atyping.inferType(vrttyp.refinementType))))))
          val voidRes: Set[TypeResult[VoideableRefinementType]] =
              if (vrttyp.possiblyVoid) Set(ExceptionalResult(Error(Set(OtherError)))) else Set()
          val typRes: Set[TypeResult[VoideableRefinementType]] = {
            rotyp match {
              case MapRefinementType(keyType, valueType) => updateOnMap(keyType, valueType)
              case ValueRefinementType => Set(exRes) ++ updateOnMap(ValueRefinementType, ValueRefinementType)
              case _ => Set(exRes)
            }
          }
          voidRes ++ typRes
        case FieldAccessPath(fieldName) =>
          def updateFieldOnType(dtname: TypeName, refinenameopt: Option[Refinement]): Set[TypeResult[VoideableRefinementType]] = {
            val refinementDef = refinenameopt.fold(dataTypeDefToRefinementDef(dtname, typememoriesops.datatypes(dtname)))(refinements.definitions)
            refinementDef.conss.toSet[(ConsName, List[RefinementType])].flatMap[TypeResult[VoideableRefinementType], Set[TypeResult[VoideableRefinementType]]] { case (cons, chrtys) =>
              val (_, pars) = module.constructors(cons)
              val index = pars.indexWhere(_.name == fieldName)
              if (index < 0) { Set(ExceptionalResult(Error(Set(FieldError(rotyp, fieldName))))) }
              else {
                updatePath(chrtys(index), rpaths, vrttyp).flatMap[TypeResult[VoideableRefinementType], Set[TypeResult[VoideableRefinementType]]] {
                  case SuccessResult(ntyp) =>
                    val posEx: Set[TypeResult[VoideableRefinementType]] = {
                      if (atyping.checkType(ntyp.refinementType, pars(index).typ).contains(false))
                        Set(ExceptionalResult(Error(Set(TypeError(ntyp, pars(index).typ)))))
                      else Set()
                    }
                    val posSuc: Set[TypeResult[VoideableRefinementType]] = {
                      if (atyping.checkType(ntyp.refinementType, pars(index).typ).contains(true)) {
                        val voidRes: Set[TypeResult[VoideableRefinementType]] =
                          if (ntyp.possiblyVoid) Set(ExceptionalResult(Error(Set(OtherError)))) else Set()
                        val newRn = refinements.newRefinement(dtname)
                        val newrhs = Map(cons -> chrtys.updated(index, ntyp.refinementType))
                        val nrno = addRefinement(dtname, newRn, newrhs)
                        val posRes: Set[TypeResult[VoideableRefinementType]] =
                          Set(SuccessResult(VoideableRefinementType(possiblyVoid = false, DataRefinementType(dtname, nrno))))
                        voidRes ++ posRes
                      } else Set()
                    }
                    posEx ++ posSuc
                  case ExceptionalResult(exres) => Set(ExceptionalResult(exres))
                }
              }
            }
          }
          val exRes: TypeResult[VoideableRefinementType] = ExceptionalResult(Error(Set(FieldError(rotyp, fieldName))))
          val voidRes: Set[TypeResult[VoideableRefinementType]] =
              if (vrttyp.possiblyVoid) Set(ExceptionalResult(Error(Set(OtherError)))) else Set()
          val tyRes: Set[TypeResult[VoideableRefinementType]] = {
            rotyp match {
              case DataRefinementType(dn, rno) => updateFieldOnType(dn, rno)
              case ValueRefinementType =>
                Set(ExceptionalResult(Error(Set(FieldError(rotyp, fieldName))))) ++ module.datatypes.keySet.filter { dt =>
                  module.datatypes(dt).exists { cons =>
                    val (_, pars) = module.constructors(cons)
                    pars.exists(_.name == fieldName)
                  }
                }.flatMap(dn => updateFieldOnType(dn, None))
              case _ => Set(exRes)
            }
          }
          voidRes ++ tyRes
      }
  }

  def evalAssign(localVars: Map[VarName, Type], store: TypeStore, assgn: Assignable, targetexpr: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val assignablemems = evalAssignable(localVars, store, assgn, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(assignablemems.memories.flatMap { case TypeMemory(assgnres, store__) =>
        assgnres match {
          case SuccessResult(path) =>
            val targetmems = evalLocal(localVars, store__, targetexpr, funMemo)
            Set(Lattice[TypeMemories[VoideableRefinementType]].lubs(targetmems.memories.flatMap{ case TypeMemory(targetres, store_) =>
              targetres match {
                case SuccessResult(typ) =>
                  val newTypRes: Set[TypeResult[VoideableRefinementType]] =
                    if (path.accessPaths.isEmpty) {
                      Set(SuccessResult(typ))
                    } else {
                      getVar(store_, path.varName).fold[Set[TypeResult[VoideableRefinementType]]](Set(ExceptionalResult(Error(Set(UnassignedVarError(path.varName)))))) {
                        case VoideableRefinementType(possUnassigned, otyp) =>
                          (if (possUnassigned) Set(ExceptionalResult(Error(Set(UnassignedVarError(path.varName))))) else Set()) ++
                            updatePath(otyp, path.accessPaths, typ)
                      }
                    }
                  newTypRes.flatMap {
                    case SuccessResult(newvrt) =>
                      // TODO provide internal error instead of exception from math lookup of unknown field
                      val staticVarTy = if (localVars.contains(path.varName)) localVars(path.varName) else module.globalVars(path.varName)
                      val exRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(TypeError(newvrt, staticVarTy)))), store_)
                      val posEx: Set[TypeMemories[VoideableRefinementType]] =
                        if (atyping.checkType(newvrt.refinementType, staticVarTy).contains(false)) Set(TypeMemories(Set(exRes)))
                        else Set()
                      val posSuc: Set[TypeMemories[VoideableRefinementType]] =
                        if (atyping.checkType(newvrt.refinementType, staticVarTy).contains(true)) {
                          Set(TypeMemories(Set(TypeMemory(SuccessResult(newvrt), setVar(store_, path.varName, newvrt)))))
                        } else Set()
                      posEx ++ posSuc
                    case ExceptionalResult(exres) =>
                      Set(TypeMemories(Set(TypeMemory[VoideableRefinementType](ExceptionalResult(exres), store_))))
                  }
                case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
              }
            }))
          case ExceptionalResult(exres) =>
            Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
        }
    })
  }

  def evalIf(localVars: Map[VarName, Type], store: TypeStore, cond: Expr, thenB: Expr, elseB: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val condmems = evalLocal(localVars, store, cond, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(condmems.memories.flatMap { case TypeMemory(condres, store__) =>
      condres match {
        case SuccessResult(condvrty) =>
          val exRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(TypeError(condvrty, DataType("Bool"))))), store__)
          def sucRes(refinenameopt: Option[Refinement]): TypeMemories[VoideableRefinementType] = {
            val refinementDef = refinenameopt.fold(dataTypeDefToRefinementDef("Bool",typememoriesops.datatypes("Bool")))(refinements.definitions)
            Lattice[TypeMemories[VoideableRefinementType]].lubs(refinementDef.conss.keySet.map {
              case "true" => evalLocal(localVars, store__, thenB, funMemo)
              case "false" => evalLocal(localVars, store__, elseB, funMemo)
              case _ => throw NonNormalFormMemories
            })
          }
          val voidRes: Set[TypeMemories[VoideableRefinementType]] =
            if (condvrty.possiblyVoid) Set(TypeMemories(Set(exRes))) else Set()
          val tyRes: Set[TypeMemories[VoideableRefinementType]] = {
            condvrty.refinementType match {
              case DataRefinementType("Bool", rno) => Set(sucRes(rno))
              case ValueRefinementType => Set(TypeMemories(Set(exRes)), sucRes(None))
              case _ => Set(TypeMemories(Set(exRes)))
            }
          }
          voidRes ++ tyRes
        case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store))))
      }
    })
  }

  def evalCases(localVars: Map[VarName, Type], store: TypeStore, scrtyp: VoideableRefinementType, cases: List[Case], funMemo: FunMemo): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
    def evalCase(store: TypeStore, action: Expr, envs: Set[Map[VarName, VoideableRefinementType]]): TypeMemories[VoideableRefinementType] = {
      envs.headOption.fold(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Fail), store)))) { env =>
        val joinedstore = joinStores(store, TypeStoreV(env))
        val actmems = evalLocal(localVars, joinedstore, action, funMemo)
        val actress = actmems.memories.map { case TypeMemory(actres, store_) =>
          actres match {
            case ExceptionalResult(Fail) => evalCase(store, action, envs.tail)
            case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(actres, dropVars(store_, env.keySet))))
          }
        }
        Lattice[TypeMemories[VoideableRefinementType]].lubs(actress)
      }
    }
    val casesres: (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = cases match {
      case Nil => (scrtyp, TypeMemories(Set(TypeMemory(ExceptionalResult(Fail), store))))
      case Case(cspatt, csaction) :: css =>
        val envss = matchPatt(store, scrtyp, cspatt)
        val resmems = envss.map { case (refinestore, refinescrtyp, envs) =>
          val casemems: TypeMemories[VoideableRefinementType] = evalCase(refinestore, csaction, envs)
          val resmems = casemems.memories.map { case TypeMemory(cres, store_) =>
            cres match {
              case ExceptionalResult(Fail) => evalCases(localVars, refinestore, refinescrtyp, css, funMemo)
              case _ => (Lattice[VoideableRefinementType].bot, TypeMemories[VoideableRefinementType](Set(TypeMemory(cres, store_))))
            }
          }
          val resmemslub = Lattice[TypeMemories[VoideableRefinementType]].lubs(resmems.map(_._2))
          val failreslub = Lattice[VoideableRefinementType].lubs(resmems.map(_._1))
          (failreslub, resmemslub)
        }
        val resmemslub = Lattice[TypeMemories[VoideableRefinementType]].lubs(resmems.map(_._2))
        val failreslub = Lattice[VoideableRefinementType].lubs(resmems.map(_._1))
        (failreslub, resmemslub)
    }
    casesres
  }

  def evalSwitch(localVars: Map[VarName, Type], store: TypeStore, scrutinee: Expr, cases: Seq[Case], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val scrmems = evalLocal(localVars, store, scrutinee, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(scrmems.memories.flatMap { case TypeMemory(scrres, store__) =>
        scrres match {
          case SuccessResult(scrval) =>
            val (failvrty, casemems) = evalCases(localVars, store__, scrval, cases.toList, funMemo)
            Set(Lattice[TypeMemories[VoideableRefinementType]].lubs(casemems .memories.map { case TypeMemory(caseres, store_) =>
                caseres match {
                  case SuccessResult(caseval) =>
                    TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(caseval), store_)))
                  case ExceptionalResult(exres) =>
                    exres match {
                      case Fail =>
                        if (!Lattice[VoideableRefinementType].isBot(failvrty))
                          TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = true, NoRefinementType)), store_)))
                        else Lattice[TypeMemories[VoideableRefinementType]].bot
                      case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_)))
                    }
                }
            }))
          case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
        }
    })
  }

  def memoVisitKey(rtyp: VoideableRefinementType): (Type, Set[ConsName]) = {
    (atyping.inferType(rtyp.refinementType),
      if (precise) possibleConstructors(rtyp.refinementType)
      else Set())
  }

  def combineVals(ctyres: TypeResult[VoideableRefinementType], ctysres: TypeResult[List[VoideableRefinementType]],
                  cty: VoideableRefinementType, ctys: List[VoideableRefinementType]): (List[VoideableRefinementType], TypeResult[AList[VoideableRefinementType]]) = {
    if (ctyres == ExceptionalResult(Fail) &&
          ctysres == ExceptionalResult(Fail)) {
      (cty :: ctys, ExceptionalResult(Fail))
    } else {
      val cty2 = ifFail(ctyres, cty)
      val ctys2 = ifFail[List[VoideableRefinementType]](ctysres, ctys)
      (AList.getList(AList.sizedBot[VoideableRefinementType](1 + ctys.length)), SuccessResult(AListVals(cty2 :: ctys2)))
    }
  }

  def evalTD(localVars: Map[VarName, Type], store: TypeStore, scrtyp: VoideableRefinementType, cases: List[Case], break: Boolean, funMemo: FunMemo): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
    def evalTDAll(vrtypes: List[RefinementType], store: TypeStore, memo: Map[(Type, Set[ConsName]), (VoideableRefinementType, (VoideableRefinementType, TypeMemories[VoideableRefinementType]))]): (List[VoideableRefinementType], TypeMemories[List[VoideableRefinementType]]) = {
      vrtypes match {
        case Nil => (List(), TypeMemories[List[VoideableRefinementType]](Set(TypeMemory(ExceptionalResult(Fail), store))))
        case cty::ctys =>
          val (failresty, ctymems) = memoFix(VoideableRefinementType(possiblyVoid = false, cty), store, memo, reccall = true)
          val (failrestys, alistMems) = Lattice[(AList[VoideableRefinementType], TypeMemories[AList[VoideableRefinementType]])].lubs(ctymems.memories.map {
            case TypeMemory(ctyres, store__) =>
              def evalRest = {
                Lattice[(AList[VoideableRefinementType], TypeMemories[AList[VoideableRefinementType]])].lubs{
                  val (failrestys, ctysmems) = evalTDAll(ctys, store__, memo)
                    ctysmems.memories.map { case TypeMemory(ctysres, store_) =>
                      ctysres match {
                        case SuccessResult(_) | ExceptionalResult(Fail)  =>
                          val (failrestys2, combineres) = combineVals(ctyres, ctysres, failresty, failrestys)
                          (AListVals(failrestys2), TypeMemories[AList[VoideableRefinementType]](Set(TypeMemory(combineres, store_))))
                        case ExceptionalResult(exres) =>
                          (AList.sizedBot[VoideableRefinementType](1 + ctys.length),
                            TypeMemories[AList[VoideableRefinementType]](Set(TypeMemory(ExceptionalResult(exres), store_))))
                      }
                    }
                }
              }
              ctyres match {
                case SuccessResult(cty_) =>
                  if (break)
                    (AList.sizedBot[VoideableRefinementType](1 + ctys.length), TypeMemories[AList[VoideableRefinementType]](
                        Set(TypeMemory(SuccessResult(AListVals(cty_ :: ctys.map(VoideableRefinementType(possiblyVoid = false, _)))), store__))))
                  else evalRest
                case ExceptionalResult(Fail) => evalRest
                case ExceptionalResult(exres) =>
                  (AList.sizedBot[VoideableRefinementType](1 + ctys.length),
                    TypeMemories[AList[VoideableRefinementType]](Set(TypeMemory(ExceptionalResult(exres), store__))))
              }
          })
          (AList.getList(AList.botToSizedBot(failrestys, 1 + ctys.length)), unAlistMems(alistMems))
      }
    }

    def memoFix(scrtyp: VoideableRefinementType, store: TypeStore,
                memo: Map[(Type, Set[ConsName]), (VoideableRefinementType, (VoideableRefinementType, TypeMemories[VoideableRefinementType]))],
                reccall: Boolean = false): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
      def go(scrtyp: VoideableRefinementType, prevRes: (VoideableRefinementType, TypeMemories[VoideableRefinementType]), loopcount: Int): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
        val (failresty, scmems) = evalCases(localVars, store, scrtyp, cases, funMemo)
        val newRes = Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(scmems.memories.map { case TypeMemory(scres, store__) =>
            def evalRest(scres: TypeResult[VoideableRefinementType], tp: VoideableRefinementType) = {
              val ty = ifFail(scres, tp)
              val res = Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(children(ty).map { case (nnrtyp, ctys) =>
                val (failrestys, chres) = evalTDAll(ctys, store__, memo.updated(memoVisitKey(scrtyp), (scrtyp, prevRes)))
                Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(chres.memories.map { case TypeMemory(ctysres, store_) =>
                  ctysres match {
                    case SuccessResult(ctys2) =>
                      (Lattice[VoideableRefinementType].bot, TypeMemories[VoideableRefinementType](reconstruct(nnrtyp, ctys2).map(TypeMemory(_, store_))))
                    case ExceptionalResult(Fail) =>
                      val newscres = scres match {
                        case SuccessResult(_) => SuccessResult(safeReconstruct(nnrtyp, failrestys))
                        case ExceptionalResult(exres) => ExceptionalResult(exres)
                      }
                      (safeReconstruct(nnrtyp, failrestys), TypeMemories[VoideableRefinementType](Set(TypeMemory(newscres, store_))))
                    case ExceptionalResult(exres) =>
                      (Lattice[VoideableRefinementType].bot, TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
                  }
                })
              })
              res
            }
            scres match {
              case SuccessResult(ty) =>
                if (break)
                  (Lattice[VoideableRefinementType].bot, TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(ty), store__))))
                else evalRest(scres, ty)
              case ExceptionalResult(Fail) => evalRest(scres, failresty)
              case ExceptionalResult(exres) =>
                (Lattice[VoideableRefinementType].bot, TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
            }
        })
        if (Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].<=(newRes, prevRes)) newRes
        else {
          val widened = Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].widen(prevRes, newRes)
          go(scrtyp, widened, loopcount = loopcount + 1)
        }
      }
      memo.get(memoVisitKey(scrtyp)).fold(
        go(scrtyp, Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].bot, loopcount = 0)) { case (prevscrtyp, prevres) =>
          if (Lattice[VoideableRefinementType].<=(scrtyp, prevscrtyp)) prevres
          else {
            val scrtyplub = Lattice[VoideableRefinementType].widen(prevscrtyp, scrtyp)
            go(scrtyplub, Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].bot, loopcount = 0)
          }
      }
    }
    memoFix(scrtyp, store, Map())
  }


  def evalBU(localVars: Map[VarName, Type], store: TypeStore, scrtyp: VoideableRefinementType, cases: List[Case], break: Boolean, funMemo: FunMemo): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
    def evalBUAll(rtys: List[RefinementType], store: TypeStore, memo: Map[(Type, Set[ConsName]), (VoideableRefinementType, (VoideableRefinementType, TypeMemories[VoideableRefinementType]))]): (List[VoideableRefinementType], TypeMemories[List[VoideableRefinementType]]) = {
      rtys match {
        case Nil => (List(), TypeMemories[List[VoideableRefinementType]](Set(TypeMemory(ExceptionalResult(Fail), store))))
        case crty :: crtys =>
          val (failresty, crtymems) = memoFix(store, VoideableRefinementType(possiblyVoid = false, crty), memo)
          val (failrestys, alistMems) = Lattice[(AList[VoideableRefinementType], TypeMemories[AList[VoideableRefinementType]])].lubs(crtymems.memories.map {
            case TypeMemory(crtyres, store__) =>
              def evalRest: (AList[VoideableRefinementType], TypeMemories[AList[VoideableRefinementType]]) = {
                val (failrestys, chres) = evalBUAll(crtys, store__, memo)
                Lattice[(AList[VoideableRefinementType], TypeMemories[AList[VoideableRefinementType]])].lubs(chres.memories.map { case TypeMemory(crtysres, store_) =>
                  crtysres match {
                    case SuccessResult(_) | ExceptionalResult(Fail) =>
                      val (failrestys2, combineres) = combineVals(crtyres, crtysres, failresty, failrestys)
                      (AListVals(failrestys2), TypeMemories[AList[VoideableRefinementType]](Set(TypeMemory(combineres, store_))))
                    case ExceptionalResult(exres) =>
                      (AList.sizedBot[VoideableRefinementType](1 + crtys.length),
                        TypeMemories[AList[VoideableRefinementType]](Set(TypeMemory(ExceptionalResult(exres), store_))))
                  }
                })
              }
              crtyres match {
                case SuccessResult(crty_) =>
                  if (break)
                    (AList.sizedBot[VoideableRefinementType](1 + crtys.length), TypeMemories[AList[VoideableRefinementType]](Set(TypeMemory(
                      SuccessResult(AListVals(crty_ :: crtys.map(VoideableRefinementType(possiblyVoid = false, _)))), store__))))
                  else evalRest
                case ExceptionalResult(Fail) => evalRest
                case ExceptionalResult(exres) =>
                  (AList.sizedBot[VoideableRefinementType](1 + crtys.length),
                    TypeMemories[AList[VoideableRefinementType]](Set(TypeMemory(ExceptionalResult(exres), store__))))
              }
          })
          (AList.getList(AList.botToSizedBot(failrestys, 1 + crtys.length)), unAlistMems(alistMems))
      }
    }

    // TODO Visit state may dependend on the store as well, because of store-dependent pattern matching
    def memoFix(store: TypeStore, scrtyp: VoideableRefinementType, memo: Map[(Type, Set[ConsName]), (VoideableRefinementType, (VoideableRefinementType, TypeMemories[VoideableRefinementType]))]) = {
      def go(scrtyp: VoideableRefinementType, prevRes: (VoideableRefinementType, TypeMemories[VoideableRefinementType]), loopcount: Int): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
        val newRes = Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(children(scrtyp).map {
          case (nnrtyp, crtys) =>
            val (failrestys, chrmems) = evalBUAll(crtys, store, memo.updated(memoVisitKey(scrtyp), (scrtyp, prevRes)))
            Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(chrmems.memories.map {
              case TypeMemory(crtyres, store__) =>
                crtyres match {
                  case SuccessResult(crtys2) =>
                    if (break) {
                      (Lattice[VoideableRefinementType].bot,
                        TypeMemories[VoideableRefinementType](reconstruct(nnrtyp, crtys2).map(TypeMemory(_, store__))))
                    } else {
                      Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(reconstruct(nnrtyp, crtys2).map {
                        case SuccessResult(rty) =>
                          val (failresty, rtyresmems) = evalCases(localVars, store__, rty, cases, funMemo)
                          Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(rtyresmems.memories.map {
                            case TypeMemory(rtyres, store_) =>
                              rtyres match {
                                case SuccessResult(_) | ExceptionalResult(Fail) =>
                                  (Lattice[VoideableRefinementType].bot,
                                    TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(ifFail(rtyres, failresty)), store_))))
                                case ExceptionalResult(exres) =>
                                  (Lattice[VoideableRefinementType].bot,
                                    TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
                              }
                          })
                        case ExceptionalResult(exres) =>
                          (Lattice[VoideableRefinementType].bot,
                            TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
                      })
                    }
                  case ExceptionalResult(Fail) =>
                    evalCases(localVars, store__, safeReconstruct(nnrtyp, failrestys), cases, funMemo)
                  case ExceptionalResult(exres) =>
                    (Lattice[VoideableRefinementType].bot,
                      TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
                }
            })
        })
        if (Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].<=(newRes, prevRes)) newRes
        else {
          val widened = Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].widen(prevRes, newRes)
          go(scrtyp, widened, loopcount = loopcount + 1)
        }
      }
      memo.get(memoVisitKey(scrtyp)).fold(go(scrtyp, Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].bot, loopcount = 0)) { case (prevscrtyp, prevres) =>
        if (Lattice[VoideableRefinementType].<=(scrtyp, prevscrtyp)) prevres
        else {
          val scrtyplub = Lattice[VoideableRefinementType].widen(prevscrtyp, scrtyp)
          go(scrtyplub, Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].bot, loopcount = 0)
        }
      }
    }
    memoFix(store, scrtyp, Map())
  }

  def evalVisitStrategy(strategy: Strategy, localVars: Map[VarName, Type], store: TypeStore, scrtyp: VoideableRefinementType, cases: List[Case], funMemo: FunMemo): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
    def loop(store: TypeStore, scrtyp: VoideableRefinementType, evalIn : (Map[VarName, Type], TypeStore, VoideableRefinementType, List[Case], Boolean, FunMemo) => (VoideableRefinementType, TypeMemories[VoideableRefinementType])): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
      def memoFix(store: TypeStore, scryp: VoideableRefinementType, memo: Map[TypeStore, (VoideableRefinementType, TypeMemories[VoideableRefinementType])]): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
        def go(prevRes: (VoideableRefinementType, TypeMemories[VoideableRefinementType])): (VoideableRefinementType, TypeMemories[VoideableRefinementType]) = {
          val (failresty, resmems) = evalIn(localVars, store, scrtyp, cases, /* break = */ false, funMemo)
          val newRes = Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lubs(resmems.memories.map { case TypeMemory(res, store_) =>
            res match {
              case SuccessResult(resty) =>
                val eqres = refineEq(scrtyp, resty)
                val widenedstore = Lattice[TypeStore].lub(store, store_)
                eqres.fold[(VoideableRefinementType, TypeMemories[VoideableRefinementType])](memoFix(widenedstore, resty, memo.updated(store, prevRes))) { reseq =>
                  Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].lub(
                    (Lattice[VoideableRefinementType].bot, TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(reseq), store_)))),
                    memoFix(widenedstore, resty, memo.updated(store, prevRes)))
                }
              case ExceptionalResult(exres) => (failresty, TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
            }
          })
          if (Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].<=(newRes, prevRes)) newRes
          else go(Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].widen(prevRes, newRes))
        }
        memo.getOrElse(store, go(Lattice[(VoideableRefinementType, TypeMemories[VoideableRefinementType])].bot))
      }
      memoFix(store, scrtyp, Map())
    }
    strategy match {
      case TopDown => evalTD(localVars, store, scrtyp, cases, break = false, funMemo)
      case TopDownBreak => evalTD(localVars, store, scrtyp, cases, break = true, funMemo)
      case BottomUp => evalBU(localVars, store, scrtyp, cases, break = false, funMemo)
      case BottomUpBreak => evalBU(localVars, store, scrtyp, cases, break = true, funMemo)
      case Innermost =>
        loop(store, scrtyp, evalBU)
      case Outermost =>
        loop(store, scrtyp, evalTD)
    }
  }

  def evalVisit(localVars: Map[VarName, Type], store: TypeStore, strategy: Strategy, scrutinee: Expr, cases: Seq[Case], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val scrmems = evalLocal(localVars, store, scrutinee, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(scrmems.memories.map { case TypeMemory(scrres, store__) =>
      scrres match {
        case SuccessResult(scrtyp) =>
          val (failresty, casemems) = evalVisitStrategy(strategy, localVars, store__, scrtyp, cases.toList, funMemo)
          Lattice[TypeMemories[VoideableRefinementType]].lubs(casemems.memories.map { case TypeMemory(caseres, store_) =>
              caseres match {
                case SuccessResult(casetyp) => TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(casetyp), store_)))
                case ExceptionalResult(exres) =>
                  exres match {
                    case Fail => TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(failresty), store_)))
                    case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_)))
                  }
              }
          })
        case ExceptionalResult(exres) => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__)))
      }
    })
  }

  def evalBlock(localVars: Map[VarName, Type], store: TypeStore, vardefs: Seq[Parameter], exprs: Seq[Expr], funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val localVars_ = localVars ++ vardefs.map(par => par.name -> par.typ)
    val resmems = evalLocalAll(localVars_, store, exprs, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(resmems.memories.map[TypeMemories[VoideableRefinementType], Set[TypeMemories[VoideableRefinementType]]] { case TypeMemory(res, store__) =>
        val store_ = dropVars(store__, vardefs.map(_.name).toSet)
        res match {
          case SuccessResult(typs) =>
            TypeMemories(Set(TypeMemory(SuccessResult(typs.lastOption.getOrElse(VoideableRefinementType(possiblyVoid = true, NoRefinementType))), store_)))
          case ExceptionalResult(exres) => TypeMemories(Set(TypeMemory(ExceptionalResult(exres), store_)))
        }
    })
  }

  def evalGenerator(localVars: Map[VarName, Type], store: TypeStore, gen: Generator, funMemo: FunMemo): TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]] = {
    gen match {
      case MatchAssign(patt, target) =>
        val tmems = evalLocal(localVars, store, target, funMemo)
        import Powerset._
        Lattice[TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]]].lubs(tmems.memories.flatMap { case TypeMemory(tres, store_) =>
          tres match {
            case SuccessResult(ttyp) =>
              matchPatt(store_, ttyp, patt).map { case (refinestore, _, envs) =>
                TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]](Set(TypeMemory(SuccessResult(Set(envs)), refinestore)))
              }
            case ExceptionalResult(exres) =>
              Set(TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]](Set(TypeMemory(ExceptionalResult(exres), store_))))
          }
        })
      case EnumAssign(varname, target) =>
        val tmems = evalLocal(localVars, store, target, funMemo)
        import Powerset._
        Lattice[TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]]].lubs(
          tmems.memories.flatMap[TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]], Set[TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]]]] {
            case TypeMemory(tres, store_) =>
              tres match {
                case SuccessResult(vrttyp) =>
                  val exRes: TypeMemory[Set[Set[Map[VarName, VoideableRefinementType]]]] =
                    TypeMemory(ExceptionalResult(Error(Set(NotEnumerableError(vrttyp)))), store_)
                  val voidRes: Set[TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]]] =
                    if (vrttyp.possiblyVoid) Set(TypeMemories(Set(exRes))) else Set()
                  val tyRes: Set[TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]]] = {
                    vrttyp.refinementType match {
                      case ListRefinementType(elementType) =>
                        Set(TypeMemories(Set(TypeMemory(SuccessResult(Set(Set(Map(varname -> VoideableRefinementType(possiblyVoid = false, elementType))))), store_))))
                      case SetRefinementType(elementType) =>
                        Set(TypeMemories(Set(TypeMemory(SuccessResult(Set(Set(Map(varname -> VoideableRefinementType(possiblyVoid = false, elementType))))), store_))))
                      case MapRefinementType(keyType, _) =>
                        Set(TypeMemories(Set(TypeMemory(SuccessResult(Set(Set(Map(varname -> VoideableRefinementType(possiblyVoid = false, keyType))))), store_))))
                      case  ValueRefinementType =>
                        Set(TypeMemories(Set(exRes,
                          TypeMemory(SuccessResult(Set(Set(Map(varname -> VoideableRefinementType(possiblyVoid = false, ValueRefinementType))))), store_))))
                      case _ =>
                        Set(TypeMemories(Set(exRes)))
                    }
                  }
                  voidRes ++ tyRes
                case ExceptionalResult(exres) =>
                  Set(TypeMemories[Set[Set[Map[VarName, VoideableRefinementType]]]](Set(TypeMemory(ExceptionalResult(exres), store_))))
              }
        })
    }
  }

  def evalEach(localVars: Map[VarName, Type], store: TypeStore, envss: Set[Set[Map[VarName, VoideableRefinementType]]], body: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    def evalOnEnv(envs: Set[Map[VarName, VoideableRefinementType]]): TypeMemories[VoideableRefinementType] = {
      // TODO Find a way to have the go fixedpoint calculation outside the inner memoization/regular tree calculation
      def memoFix(store: TypeStore, memo: Map[TypeStore, TypeMemories[VoideableRefinementType]]): TypeMemories[VoideableRefinementType] = {
        def go(prevRes: TypeMemories[VoideableRefinementType]): TypeMemories[VoideableRefinementType] = {
          val itermems: TypeMemories[VoideableRefinementType] = {
            // We overapproximate order, cardinality and content, so we have to try all possible combinations in parallel
            val bodymems = Lattice[TypeMemories[VoideableRefinementType]].lubs(envs.map { env =>
              val joinedStore = joinStores(store, TypeStoreV(env))
              val bodymems1 = evalLocal(localVars, joinedStore, body, funMemo)
              TypeMemories(bodymems1.memories.map {
                case TypeMemory(bodyres, store_) => TypeMemory(bodyres, dropVars(store_, env.keySet))
              })
            })
            Lattice[TypeMemories[VoideableRefinementType]].lubs(bodymems.memories.map { case TypeMemory(bodyres, store_) =>
              bodyres match {
                case SuccessResult(_) =>
                  val widenedstore = Lattice[TypeStore].widen(store, store_)
                  memoFix(widenedstore, memo.updated(store, prevRes))
                case ExceptionalResult(exres) =>
                  exres match {
                    case Break => TypeMemories[VoideableRefinementType](
                      Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = true, NoRefinementType)), store_)))
                    case Continue =>
                      val widenedstore = Lattice[TypeStore].widen(store, store_)
                      memoFix(widenedstore, memo.updated(store, prevRes))
                    // We have to try everything again because of possible duplicates (although perhaps, it should only be
                    // envs, because it is not possible to change alternative through an iteration
                    case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_)))
                  }
              }
            })
          }
          val newRes = Lattice[TypeMemories[VoideableRefinementType]].lub(
            TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = true, NoRefinementType)), store))), itermems)
          if (Lattice[TypeMemories[VoideableRefinementType]].<=(newRes, prevRes)) newRes
          else go(Lattice[TypeMemories[VoideableRefinementType]].widen(prevRes, newRes))
        }
        memo.getOrElse(store, go(Lattice[TypeMemories[VoideableRefinementType]].bot))
      }
      memoFix(store, Map())
    }
    Lattice[TypeMemories[VoideableRefinementType]].lubs(envss.map { envs => evalOnEnv(envs) })
  }

  def evalFor(localVars: Map[VarName, Type], store: TypeStore, gen: Generator, body: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val genmems = evalGenerator(localVars, store, gen, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(genmems.memories.flatMap { case TypeMemory(genres, store__) =>
      genres match {
        case SuccessResult(envs) =>
          val bodymems = evalEach(localVars, store__, envs, body, funMemo)
          Set(TypeMemories(bodymems.memories.map[TypeMemory[VoideableRefinementType], Set[TypeMemory[VoideableRefinementType]]] { case TypeMemory(bodyres, store_) =>
            bodyres match {
              case SuccessResult(_) => TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = true, NoRefinementType)), store_)
              case ExceptionalResult(exres) => TypeMemory(ExceptionalResult(exres), store_)
            }
          }))
        case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
      }
    })
  }

  def evalWhile(localVars: Map[VarName, Type], store: TypeStore, cond: Expr, body: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    def memoFix(store: TypeStore, memo: Map[TypeStore, TypeMemories[VoideableRefinementType]]): TypeMemories[VoideableRefinementType] = {
      def go(prevRes: TypeMemories[VoideableRefinementType]): TypeMemories[VoideableRefinementType] = {
        val condmems = evalLocal(localVars, store, cond, funMemo)
        val newRes = Lattice[TypeMemories[VoideableRefinementType]].lubs(condmems.memories.flatMap { case TypeMemory(condres, store__) =>
            condres match {
              case SuccessResult(condty) =>
                val errRes = TypeMemory[VoideableRefinementType](ExceptionalResult(Error(Set(TypeError(condty, DataType("Bool"))))), store__)
                def succRes(refinenameopt : Option[Refinement]) : TypeMemories[VoideableRefinementType] = {
                  val refinementdef = refinenameopt.fold(dataTypeDefToRefinementDef("Bool", typememoriesops.datatypes("Bool")))(refinements.definitions)
                  Lattice[TypeMemories[VoideableRefinementType]].lubs(refinementdef.conss.keySet.map {
                    case "false" =>
                      TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = true, NoRefinementType)), store__)))
                    case "true" =>
                      val bodymems = evalLocal(localVars, store__, body, funMemo)
                      Lattice[TypeMemories[VoideableRefinementType]].lubs(bodymems.memories.map { case TypeMemory(bodyres, store_) =>
                        bodyres match {
                          case SuccessResult(_) =>
                            val widenedstore = Lattice[TypeStore].widen(store, store_)
                            memoFix(widenedstore, memo.updated(store, prevRes))
                          case ExceptionalResult(exres) =>
                            exres match {
                              case Break => TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = true, NoRefinementType)), store_)))
                              case Continue =>
                                val widenedstore = Lattice[TypeStore].widen(store, store_)
                                memoFix(widenedstore, memo.updated(store, prevRes))
                              case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_)))
                            }
                        }
                      })
                    case _ => throw NonNormalFormMemories
                  })
                }
                val voidRes: Set[TypeMemories[VoideableRefinementType]] = if (condty.possiblyVoid) Set(TypeMemories(Set(errRes))) else Set()
                val tyRes: Set[TypeMemories[VoideableRefinementType]] = {
                  condty.refinementType match {
                    case DataRefinementType("Bool", rno) => Set(succRes(rno))
                    case ValueRefinementType => Set(TypeMemories[VoideableRefinementType](Set(errRes))) ++ Set(succRes(None))
                    case _ => Set(TypeMemories[VoideableRefinementType](Set(errRes)))
                  }
                }
                voidRes ++ tyRes
              case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
            }
        })
        if (Lattice[TypeMemories[VoideableRefinementType]].<=(newRes, prevRes)) newRes
        else go(Lattice[TypeMemories[VoideableRefinementType]].widen(prevRes, newRes))
      }
      memo.getOrElse(store, go(Lattice[TypeMemories[VoideableRefinementType]].bot))
    }
    memoFix(store, Map())
  }

  def evalSolve(localVars: Map[VarName, Type], store: TypeStore, vars: Seq[VarName], body: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    def memoFix(store: TypeStore, memo: Map[TypeStore, TypeMemories[VoideableRefinementType]]): TypeMemories[VoideableRefinementType] = {
      def go(prevRes: TypeMemories[VoideableRefinementType]): TypeMemories[VoideableRefinementType] = {
        val bodymems = evalLocal(localVars, store, body, funMemo)
        val newRes = Lattice[TypeMemories[VoideableRefinementType]].lubs(bodymems.memories.flatMap { case TypeMemory(bodyres, store_) =>
          bodyres match {
            case SuccessResult(t) =>
              val prevVars = vars.toList.flatMap(x => getVar(store, x).map(_.refinementType))
              val newVars = vars.toList.flatMap(x => getVar(store_, x).map(_.refinementType))
              val prevEmptyVar = vars.exists(x => getVar(store, x).isEmpty)
              val newEmptyVar = vars.exists(x => getVar(store_, x).isEmpty)
              if (prevEmptyVar || newEmptyVar)
                Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Error(Set(OtherError))), store_))))
              else {
                val prevPosEmptyVar = vars.exists(x => getVar(store, x).fold(true)(_.possiblyVoid))
                val newPosEmptyVar = vars.exists(x => getVar(store_, x).fold(true)(_.possiblyVoid))
                val posEx: Set[TypeMemories[VoideableRefinementType]] = if (prevPosEmptyVar || newPosEmptyVar)
                  Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Error(Set(OtherError))), store_))))
                else Set[TypeMemories[VoideableRefinementType]]()
                val posSuc: Set[TypeMemories[VoideableRefinementType]] = {
                  val widenedstore = Lattice[TypeStore].widen(store, store_)
                  val refinedeqvars = vars.toList.zip(prevVars.zip(newVars)).foldLeftM[Option, Map[VarName, VoideableRefinementType]](Map()) { (prevrefinedvarvals, v3) =>
                    val (varname, (pvv, nvv)) = v3
                    refineEq(VoideableRefinementType(possiblyVoid = false, pvv),
                      VoideableRefinementType(possiblyVoid = false, nvv)).map { eqval =>
                      prevrefinedvarvals.updated(varname, eqval)
                    }
                  }
                  Set(memoFix(widenedstore, memo.updated(store, prevRes))) ++
                    refinedeqvars.fold(Set[TypeMemories[VoideableRefinementType]]()) { refinedeqvarvals =>
                        Set(TypeMemories(Set(TypeMemory(SuccessResult(t), joinStores(store_, TypeStoreV(refinedeqvarvals))))))
                    }
                }
                posEx ++ posSuc
              }
            case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
          }
        })
        if (Lattice[TypeMemories[VoideableRefinementType]].<=(newRes, prevRes)) newRes
        else go(Lattice[TypeMemories[VoideableRefinementType]].widen(prevRes, newRes))
      }
      memo.getOrElse(store, go(Lattice[TypeMemories[VoideableRefinementType]].bot))
    }
    memoFix(store, Map())
  }

  def evalThrow(localVars: Map[VarName, Type], store: TypeStore, evl: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val valmems = evalLocal(localVars, store, evl, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(valmems.memories.flatMap { case TypeMemory(valres, store_) =>
      valres match {
        case SuccessResult(valty) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(Throw(valty)), store_))))
        case ExceptionalResult(exres) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store_))))
      }
    })
  }

  def evalTryCatch(localVars: Map[VarName, Type], store: TypeStore, tryB: Expr, catchVar: VarName, catchB: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val trymems = evalLocal(localVars, store, tryB, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(trymems.memories.flatMap { case TypeMemory(tryres, store__) =>
        tryres match {
          case SuccessResult(trytyp) => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(trytyp), store__))))
          case ExceptionalResult(exres) =>
            exres match {
              case Throw(throwtyp) =>
                val updstore__ = setVar(store__, catchVar, throwtyp)
                Set(TypeMemories[VoideableRefinementType](evalLocal(localVars, updstore__, catchB, funMemo).memories.map { case TypeMemory(res, store_) =>
                    TypeMemory(res, dropVars(store_, Set(catchVar)))
                }))
              case _ => Set(TypeMemories[VoideableRefinementType](Set(TypeMemory(ExceptionalResult(exres), store__))))
            }
        }
    })
  }

  def evalTryFinally(localVars: Map[VarName, Type], store: TypeStore, tryB: Expr, finallyB: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    val trymems = evalLocal(localVars, store, tryB, funMemo)
    Lattice[TypeMemories[VoideableRefinementType]].lubs(trymems.memories.map { case TypeMemory(tryres, store__) =>
        val finmems = evalLocal(localVars, store__, finallyB, funMemo)
        Lattice[TypeMemories[VoideableRefinementType]].lubs(finmems.memories.map { case TypeMemory(finres, store_) =>
          finres match {
            case SuccessResult(_) => TypeMemories(Set(TypeMemory[VoideableRefinementType](tryres, store_)))
            case ExceptionalResult(exres) => TypeMemories(Set(TypeMemory[VoideableRefinementType](ExceptionalResult(exres), store_)))
          }
        })
    })
  }

  def evalAssert(localVars: Map[VarName, Type], store: TypeStore, cond: Expr): TypeMemories[VoideableRefinementType] =
    TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = true, NoRefinementType)), store))) // Ignore assertions for now

  def evalLocalAll(localVars: Map[VarName, Type], store: TypeStore, exprs: Seq[Expr], funMemo: FunMemo): TypeMemories[List[VoideableRefinementType]] = {
    exprs.toList.foldLeft[TypeMemories[List[VoideableRefinementType]]](TypeMemories(Set(TypeMemory(SuccessResult(List()), store)))) { (mems, e) =>
      val flatMems = Lattice[TypeMemories[Flat[List[VoideableRefinementType]]]].lubs(mems.memories.map[TypeMemories[Flat[List[VoideableRefinementType]]], Set[TypeMemories[Flat[List[VoideableRefinementType]]]]] {
        case TypeMemory(prevres, store__) =>
          prevres match {
            case SuccessResult(tys) =>
              val emems = evalLocal(localVars, store__, e, funMemo)
              Lattice[TypeMemories[Flat[List[VoideableRefinementType]]]].lubs(emems.memories.map[TypeMemories[Flat[List[VoideableRefinementType]]], Set[TypeMemories[Flat[List[VoideableRefinementType]]]]] {
                case TypeMemory(res, store_) =>
                  res match {
                    case SuccessResult(ty) =>
                      TypeMemories(Set(TypeMemory(SuccessResult(FlatValue(tys :+ ty)), store_)))
                    case ExceptionalResult(exres) => TypeMemories(Set(TypeMemory(ExceptionalResult(exres), store_)))
                  }
              })
            case ExceptionalResult(exres) =>
              TypeMemories[Flat[List[VoideableRefinementType]]](Set(TypeMemory(ExceptionalResult(exres), store__)))
          }
      })
      unflatMems(flatMems) // Remove dummy Flat, since all merger of successes happens manually
    }
  }

  def evalLocal(localVars: Map[VarName, Type], store: TypeStore, expr: Expr, funMemo: FunMemo): TypeMemories[VoideableRefinementType] = {
    expr match {
      case BasicExpr(b) =>
        b match {
          case IntLit(_) =>
            TypeMemories(Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(IntType))), store)))
          case StringLit(_) =>
            TypeMemories(Set(TypeMemory(SuccessResult(VoideableRefinementType(possiblyVoid = false, BaseRefinementType(StringType))), store)))
        }
      case VarExpr(x) => evalVar(store, x)
      case FieldAccExpr(target, fieldName) => evalFieldAccess(localVars, store, target, fieldName, funMemo)
      case UnaryExpr(op, operand) => evalUnary(localVars, store, op, operand, funMemo)
      case BinaryExpr(left, op, right) => evalBinary(localVars, store, left, op, right, funMemo)
      case ConstructorExpr(name, args) => evalConstructor(localVars, store, name, args, funMemo)
      case ListExpr(elements) => evalList(localVars, store, elements, funMemo)
      case SetExpr(elements) => evalSet(localVars, store, elements, funMemo)
      case MapExpr(keyvalues) => evalMap(localVars, store, keyvalues, funMemo)
      case MapLookupExpr(emap, ekey) => evalMapLookup(localVars, store, emap, ekey, funMemo)
      case MapUpdExpr(emap, ekey, evl) => evalMapUpdate(localVars, store, emap, ekey, evl, funMemo)
      case FunCallExpr(functionName, args) => evalFunCall(localVars, store, functionName, args, funMemo)
      case ReturnExpr(evl) => evalReturn(localVars, store, evl, funMemo)
      case AssignExpr(assgn, targetexpr) => evalAssign(localVars, store, assgn, targetexpr, funMemo)
      case IfExpr(cond, thenB, elseB) => evalIf(localVars, store, cond, thenB, elseB, funMemo)
      case SwitchExpr(scrutinee, cases) => evalSwitch(localVars, store, scrutinee, cases, funMemo)
      case VisitExpr(strategy, scrutinee, cases) => evalVisit(localVars, store, strategy, scrutinee, cases, funMemo)
      case BreakExpr => TypeMemories(Set(TypeMemory(ExceptionalResult(Break), store)))
      case ContinueExpr => TypeMemories(Set(TypeMemory(ExceptionalResult(Continue), store)))
      case FailExpr => TypeMemories(Set(TypeMemory(ExceptionalResult(Fail), store)))
      case LocalBlockExpr(vardefs, exprs) => evalBlock(localVars, store, vardefs, exprs, funMemo)
      case ForExpr(enum, body) => evalFor(localVars, store, enum, body, funMemo)
      case WhileExpr(cond, body) => evalWhile(localVars, store, cond, body, funMemo)
      case SolveExpr(vars, body) => evalSolve(localVars, store, vars, body, funMemo)
      case ThrowExpr(evl) => evalThrow(localVars, store, evl, funMemo)
      case TryCatchExpr(tryB, catchVar, catchB) => evalTryCatch(localVars, store, tryB, catchVar, catchB, funMemo)
      case TryFinallyExpr(tryB, finallyB) => evalTryFinally(localVars, store, tryB, finallyB, funMemo)
      case AssertExpr(cond) => evalAssert(localVars, store, cond)
    }
  }

  def eval(store: TypeStore, expr: Expr): TypeMemories[VoideableRefinementType] = evalLocal(Map.empty, store, expr, Map.empty)
}

object AbstractRefinementTypeExecutor {

  // TODO Handle Global Variables
  def execute(module: ModuleDef, function: VarName,
              initialRefinements: Refinements = new Refinements,
              initialStore: Option[TypeStore] = None,
              precise: Boolean = true): String \/ (Module, Refinements, TypeMemories[VoideableRefinementType]) = {
    for (transr <- ModuleTranslator.translateModule(module);
         executor = AbstractRefinementTypeExecutor(transr.semmod, initialRefinements = initialRefinements, precise = precise);
         funcDef <- transr.semmod.funs.get(function).fold(s"Unknown function $function".left[(Type, List[Parameter], FunBody)])(_.right);
         (_, pars, funcBody) = funcDef;
         funcBodyExpr <- funcBody match {
           case ExprFunBody(expr) => expr.right
           case PrimitiveFunBody => s"Primitive function $function unsupported".left
         })
      yield {
        import executor.typememoriesops._
        import refinementtypeops._
        val funcstore =
          initialStore.getOrElse(TypeStoreV(pars.map(p => p.name -> VoideableRefinementType(possiblyVoid = false, typeToRefinement(p.typ))).toMap))
        val funcBodyRes = executor.evalLocal(pars.map(p => p.name -> p.typ).toMap, funcstore, funcBodyExpr, Map.empty)
        val reslub = Lattice[TypeMemories[VoideableRefinementType]].lubs(funcBodyRes.memories.map { case TypeMemory(res, store_) =>
          res match {
            case ExceptionalResult(Return(retty)) => TypeMemories[VoideableRefinementType](Set(TypeMemory(SuccessResult(retty), store_)))
            case _ => TypeMemories[VoideableRefinementType](Set(TypeMemory(res, store_)))
          }
        })
        val relevantrefinements = relevantRefinements(executor, reslub)
        val resrefinements = new Refinements(executor.refinements.definitions.toMap.filterKeys(relevantrefinements.contains))
        (transr.semmod, resrefinements, reslub)
      }
  }

  private
  def relevantRefinements(executor: AbstractRefinementTypeExecutor, reslub: TypeMemories[VoideableRefinementType]): Set[Refinement] = {
    val allValues = reslub.memories.flatMap { case TypeMemory(res, store) =>
      val resValues = res match {
        case SuccessResult(value) => Set(value.refinementType)
        case ExceptionalResult(exres) => exres match {
          case Return(value) => Set(value.refinementType)
          case Throw(value) => Set(value.refinementType)
          case Break => Set()
          case Continue => Set()
          case Fail => Set()
          case Error(_) => Set()
        }
      }
      val storeValues = store match {
        case TypeStoreTop => Set[RefinementType]()
        case TypeStoreV(vals) => vals.values.toSet[VoideableRefinementType].map(_.refinementType)
        case TypeStoreBot => Set[RefinementType]()
      }
      resValues ++ storeValues
    }
    allValues.flatMap(v => executor.typememoriesops.refinementtypeops.allRefinements(v))
  }
}
