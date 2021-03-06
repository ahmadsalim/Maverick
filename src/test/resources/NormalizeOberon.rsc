module NormalizeOberon
/*
*  Based on https://github.com/cwi-swat/oberon0/blob/7bed47c2098c7335a6b47c3bed1329795211b53f/src/lang/oberon0/l4/normalize/NormalizeL4.rsc
*/

import String;
import List;
import Set;

data Module =
	\mod(Ident name, Declarations decls, list[Statement] body, Ident endName)
	;

data Declarations
	= decls(list[ConstDecl] consts, list[TypeDecl] types, list[VarDecl] vars, list[Procedure] procs)
	;

data ConstDecl
	= constDecl(Ident name, Expression \value)
	;

data TypeDecl
	= typeDecl(Ident name, Type \type)
	;

data VarDecl
	= varDecl(list[Ident] names, Type \type)
	;

data Type
	= user(Ident name)
	| array(Expression exp, Type \type)
    | record(list[Field] fields)
	;

data Field
	= field(list[Ident] names, Type \type)
;

data Procedure
	= proc(Ident name, list[Formal] formals, Declarations decls, list[Statement] body, Ident endName)
;

data Formal
	= formal(bool hasVar, list[Ident] names, Type \type)
;

data Statement
	= assign(Ident var, list[Selector] selectors, Expression exp)
	| ifThen(Expression condition, list[Statement] body, list[ElseIf] elseIfs, list[Statement] elsePart)
	| whileDo(Expression condition, list[Statement] body)
	| skip()
	| forDo(Ident name, Expression from, Expression to, list[Expression] by, list[Statement] body)
	| caseOf(Expression exp, list[Case] cases, list[Statement] elsePart)
	| call(Ident proc, list[Expression] args)
    | let(list[VarDecl] vars, list[Statement] body) // Is only there for the transformation
    | begin(list[Statement] body) // Is only there for transformation
	;

data Expression
	= nat(int val)
	| \true()
	| \false()
	| lookup(Ident var, list[Selector] selectors)
	| neg(Expression exp)
	| pos(Expression exp)
	| not(Expression exp)
	| mul(Expression lhs, Expression rhs)
	| div(Expression lhs, Expression rhs)
	| \mod(Expression lhs, Expression rhs)
	| amp(Expression lhs, Expression rhs)
	| add(Expression lhs, Expression rhs)
	| sub(Expression lhs, Expression rhs)
	| or(Expression lhs, Expression rhs)
	| eq(Expression lhs, Expression rhs)
	| neq(Expression lhs, Expression rhs)
	| lt(Expression lhs, Expression rhs)
	| gt(Expression lhs, Expression rhs)
	| leq(Expression lhs, Expression rhs)
	| geq(Expression lhs, Expression rhs)
	;

data Ident
	= id(str name)
;

data Selector
	= field(Ident field)
	| subscript(Expression exp)
;


data Case
	= guard(Expression guard, list[Statement] body)
;

data DeclsBody = declsbody(Declarations decls, list[Statement] body);
data ElseIf = elseif(Expression condition, list[Statement] body);

public Module normalizeBooleans(Module m) {
	return flattenBegins(liftLets(desugarBooleans(m)));
}

public Module flattenBegins(Module m) {
	return innermost visit (m) {
		case [*Statement s1, begin(b), *Statement s2] => s1 + b + s2
	}
}

public Module liftLets(Module m) {
    m = visit (m) {
		case Procedure p => ({
			switch (liftLet(p.decls, p.body)) {
				case declsbody(pdecls, pbody): {
					p.decls = pdecls;
					p.body = pbody;
				}
			};
			p;
		})
	}
	switch (liftLet(m.decls, m.body)) {
		case declsbody(mdecls, mbody): {
			m.decls = mdecls;
			m.body = mbody;
		}
	};
	return m;
}


Statement while2let(Statement s) {
	switch(s) {
		case whileDo(c, b): {
			x = uniqueName(s);
			return let([varDecl([x], user(id("BOOLEAN")))], [assign(x, [], c),
				whileDo(lookup(x, []), b + [assign(x, [], c)])]);
		}
	}
}


Statement assignCompareToIf(Statement s) {
	switch(s) {
		case assign(n,sels,compare):
			return ifThen(compare, [assign(n, sels, \true())], [], [assign(n, sels, \false())]);
	}
}

public Module desugarBooleans(Module m) {
	return innermost visit (m) {
		case assign(n, sels, not(exp)) =>
			ifThen(exp, [assign(n, sels, \false())], [], [assign(n, sels, \true())])

		case assign(n, sels, amp(lhs, rhs)) =>
			ifThen(lhs,
				[ifThen(rhs,
					[assign(n, sels, \true())], [],
					[assign(n, sels, \false())]
				)], [],
				[assign(n, sels, \false())])

		case assign(n, sels, or(lhs, rhs)) =>
			ifThen(lhs,
				[assign(n, sels, \true())], [],
				[ifThen(rhs,
					[assign(n, sels, \true())], [],
					[assign(n, sels, \false())])])

		case s:assign(n, sels, eq(lhs,rhs)) => assignCompareToIf(s)
		case s:assign(n, sels, neq(lhs,rhs)) => assignCompareToIf(s)
		case s:assign(n, sels, lt(lhs,rhs)) => assignCompareToIf(s)
		case s:assign(n, sels, gt(lhs,rhs)) => assignCompareToIf(s)
		case s:assign(n, sels, geq(lhs,rhs)) => assignCompareToIf(s)
		case s:assign(n, sels, leq(lhs,rhs)) => assignCompareToIf(s)

		case ifThen(not(c), b, [], es) =>
			ifThen(c, es, [], b)

		case ifThen(amp(lhs, rhs), b, [], es) =>
			ifThen(lhs, [ifThen(rhs, b, [], es)], [], es)

		case ifThen(or(lhs, rhs), b, [], es) =>
			ifThen(lhs, b, [], [ifThen(rhs, b, [], es)])

		case ifThen(\true(), b, [], es) => begin(b)
		case ifThen(\false(), b, [], es) => begin(es)

		case whileDo(\false(),_) => begin([])
		case s:whileDo(lookup(_,_),_) => s
		case s:whileDo(cond, _) => while2let(s)

		// Normalize elsifs
		case ifThen(c, b, [elseif(ec, eb), *eis], es) =>
				ifThen(c, b, [], [ifThen(ec, eb, eis, es)])
	}	
}

public Ident uniqueName(Statement s) {
	vars = {};
	visit (s) {
		case s2 : lookup(n, _) => {
			vars = vars + n.name;
			s2;
		}
		case s2 : assign(n, _, _) => {
			vars = vars + n.name;
			s2;
		}
	}
	return id("_" + intercalate("_", toList(vars)));
}

data LiftRes = liftRes(map[Ident, Ident] subs, Declarations decls, int cnt);

LiftRes lift(list[VarDecl] vds, Declarations decls, int cnt) {
	LiftRes res = liftRes((), decls, cnt);
	res.subs = ();
	decls.vars = decls.vars + ({
		list[VarDecl] varDecls;
		for (v <- vds) {
			for (n <- v.names) {
				nn = id(n.name + "_" + "<cnt>"); // _ prevents nameclashes
				res.cnt = res.cnt + 1;
				res.subs[n] = nn;
				varDecls = varDecls + varDecl([nn], v.\type);
			}
		};
		varDecls;
	});
	return res;
}

public DeclsBody liftLet(Declarations decls, list[Statement] stats) {
	int cnt = 0;
	
	stats = visit (stats) {
		case let(vds, b) => ({ 
			LiftRes liftRes = lift(vds, decls, cnt);
			decls = liftRes.decls;
			cnt = liftRes.cnt;
			begin(substitute(b, liftRes.subs)); 
		})
	}
	return declsbody(decls, stats);
}

public list[Statement] substitute(list[Statement] stats, map[Ident, Ident] subs) {
	return visit (stats) {
		case e : lookup(n, sels) => 
			(n in subs) ? lookup(subs[n], sels) : e
		case e : assign(n, sels, exp) => 
			(n in subs) ? assign(subs[n], sels, exp) : e
	}
}