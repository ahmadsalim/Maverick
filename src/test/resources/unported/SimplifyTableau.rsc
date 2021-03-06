module SimplifyTableau

/*
 Based on https://github.com/cwi-swat/et-al/blob/master/src/et_al/Tableau.rsc
*/

data RExpr
  = union(set[RExpr] xs)
  | isect(set[RExpr] xs)
  | diff(list[RExpr] args)
  | compose(list[RExpr] args)
  | dagger(list[RExpr] args)
  | inv(RExpr arg)
  | not(RExpr arg)
  | id(str class)
  | div(str class)
  | total(str class)
  | total(str from, str to)
  | empty()
  | implies(RExpr lhs, RExpr rhs)
  | equals(RExpr lhs, RExpr rhs)
  | base(str name, str from, str to)
;

data Signed
  = F(RExpr expr)
  | T(RExpr expr)
  ;

data Tableau
 = alt(set[Tableau] args)

 | seq(set[Tableau] args)

 // for all var, chose one of args
 | seq(Var var, set[Tableau] args)

 // for any var, do all of args
 | alt(Var var, set[Tableau] args)

 | del(Var a, Var b, RExpr arg)
 | add(Var a, Var b, RExpr arg)
 | equal(Var a, Var b)
 | nonEqual(Var a, Var b)
 ;

data Var = var(str name, str class);

Tableau simplify(Tableau tbl) {
	switch(tbl) {
		case alt({*u1, alt(set[Tableau] us), *u2}):
			return simplify(alt(u1 + us + u2));
		case seq({*u1, seq(set[Tableau] us), *u2}):
			return simplify(seq(u1 + us + u2));
		case alt({Tableau u}): return u;
		case seq({Tableau u}): return u;
		case seq({*u1, alt(set[Tableau] us), *u2}): {
		    set[Tableau] seqs = {};
			for (u <- us)
				seqs = seqs + {seq(u1 + {u} + u2)};
			return simplify(alt(seqs));
		}
		case seq({add(Var a, Var b, RExpr r), del(a, b, r), *us}):
			return simplify(alt({}));
		case alt({add(Var a, Var b, RExpr r), del(a, b, r), *us}):
			return simplify(alt({seq({})} + us));
		default: return tbl;
	};
}
