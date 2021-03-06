module NNF

data Formula = atom(str)
             | and(Formula l, Formula r)
             | or(Formula l, Formula r)
             | imp(Formula l, Formula r)
             | neg(Formula f)
             | begin(Formula f);

Formula rawnnf(Formula phi) = top-down visit(phi) {
	case neg(or(l,r)) => and(neg(l), neg(r))
	case neg(and(l,r)) => or(neg(l), neg(r))
	case neg(imp(l,r)) => and(l, neg(r))
	case neg(neg(f)) => begin(f)
	case imp(l,r) => or(neg(l), r)
};

Formula nobegin(Formula phi) = bottom-up visit(phi) {
    case begin(f) => f
};

Formula nnf(Formula phi) = nobegin(rawnnf(phi));

test bool test1() {
	Formula phi_in = neg(and(or(atom("a"), atom("b")), atom("c")));
	Formula phi_out = or(and(neg(atom("a")), neg(atom("b"))), neg(atom("c")));
	return nnf(phi_in) == phi_out;
}

test bool test2() {
	Formula phi_in = imp(neg(atom("a")), neg(atom("a")));
	Formula phi_out = or(atom("a"), neg(atom("a")));
	return nnf(phi_in) == phi_out;
}

test bool test3() {
	Formula phi_in = neg(imp(atom("a"), atom("a")));
	Formula phi_out = and(atom("a"), neg(atom("a")));
	return nnf(phi_in) == phi_out;
}

test bool test4() {
	Formula phi_in = neg(neg(atom("a")));
	Formula phi_out = atom("a");
	return nnf(phi_in) == phi_out;
}

test bool test5() {
	Formula phi_in = neg(neg(neg(neg(atom("a")))));
	Formula phi_out = atom("a");
	return nnf(phi_in) == phi_out;
}

test bool test6() {
   Formula phi_in = begin(begin(begin(atom("a"))));
   Formula phi_out = atom("a");
   return nnf(phi_in) == phi_out;
}