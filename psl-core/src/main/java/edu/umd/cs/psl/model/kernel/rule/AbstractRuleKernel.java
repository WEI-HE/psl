/*
 * This file is part of the PSL software.
 * Copyright 2011 University of Maryland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.umd.cs.psl.model.kernel.rule;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.psl.application.groundkernelstore.GroundKernelStore;
import edu.umd.cs.psl.database.DatabaseQuery;
import edu.umd.cs.psl.database.ResultList;
import edu.umd.cs.psl.model.argument.GroundTerm;
import edu.umd.cs.psl.model.argument.Term;
import edu.umd.cs.psl.model.argument.Variable;
import edu.umd.cs.psl.model.atom.Atom;
import edu.umd.cs.psl.model.atom.AtomEvent;
import edu.umd.cs.psl.model.atom.AtomEventFramework;
import edu.umd.cs.psl.model.atom.AtomManager;
import edu.umd.cs.psl.model.atom.GroundAtom;
import edu.umd.cs.psl.model.atom.VariableAssignment;
import edu.umd.cs.psl.model.formula.Formula;
import edu.umd.cs.psl.model.formula.FormulaAnalysis;
import edu.umd.cs.psl.model.formula.FormulaAnalysis.DNFClause;
import edu.umd.cs.psl.model.formula.Negation;
import edu.umd.cs.psl.model.kernel.AbstractKernel;
import edu.umd.cs.psl.model.kernel.GroundKernel;
import edu.umd.cs.psl.model.kernel.Kernel;

abstract public class AbstractRuleKernel extends AbstractKernel {
	private static final Logger log = LoggerFactory.getLogger(AbstractRuleKernel.class);
	
	protected Formula formula;
	protected final DNFClause clause;
	
	public AbstractRuleKernel(Formula f) {
		super();
		formula = f;
		FormulaAnalysis analysis = new FormulaAnalysis(new Negation(formula));
		
		if (analysis.getNumDNFClauses() > 1)
			throw new IllegalArgumentException("Formula must be a disjunction of literals (or a negative literal).");
		else
			clause = analysis.getDNFClause(0);
		
		if (!clause.getAllVariablesBound())
			throw new IllegalArgumentException("All Variables must be used at " +
					"least once as an argument for a negative literal with a " +
					"StandardPredicate.");
		
		if (clause.isGround())
			throw new IllegalArgumentException("Formula has no Variables.");
		
		if (!clause.isQueriable())
			throw new IllegalArgumentException("Formula is not a valid rule for unknown reason.");
	}
	
	@Override
	public void groundAll(AtomManager atomManager, GroundKernelStore gks) {
		ResultList res = atomManager.executeQuery(new DatabaseQuery(clause.getQueryFormula()));
		log.debug("Grounding {} instances of rule {}", res.size(), this);
		groundFormula(atomManager, gks, res, null);
	}
	
	protected void groundFormula(AtomManager atomManager, GroundKernelStore gks, ResultList res,  VariableAssignment var) {
		
		List<GroundAtom> posLiterals = new ArrayList<GroundAtom>(4);
		List<GroundAtom> negLiterals = new ArrayList<GroundAtom>(4);
		
		for (int i = 0; i < res.size(); i++) {
			for (int j = 0; j < clause.getPosLiterals().size(); j++)
				posLiterals.add(groundAtom(atomManager, clause.getPosLiterals().get(j), res, i, var));
			
			for (int j = 0; j < clause.getNegLiterals().size(); j++)
				negLiterals.add(groundAtom(atomManager, clause.getNegLiterals().get(j), res, i, var));
			
			AbstractGroundRule groundRule = groundFormulaInstance(posLiterals, negLiterals);
			GroundKernel oldrule = gks.getGroundKernel(groundRule);
			if (oldrule != null) {
				((AbstractGroundRule)oldrule).increaseGroundings();
				gks.changedGroundKernel(oldrule);
			} else {
				gks.addGroundKernel(groundRule);
			}
			
			posLiterals.clear();
			negLiterals.clear();
		}
	}
	
	protected GroundAtom groundAtom(AtomManager atomManager, Atom atom, ResultList res, int resultIndex, VariableAssignment var) {
		Term[] oldArgs = atom.getArguments();
		GroundTerm[] newArgs = new GroundTerm[atom.getArity()];
		for (int i = 0; i < oldArgs.length; i++)
			if (oldArgs[i] instanceof Variable) {
				Variable v = (Variable) oldArgs[i];
				if (var != null && var.hasVariable(v))
					newArgs[i] = var.getVariable(v);
				else
					newArgs[i] = res.get(resultIndex, (Variable) oldArgs[i]);
			}
			else if (oldArgs[i] instanceof GroundTerm)
				newArgs[i] = (GroundTerm) oldArgs[i];
			else
				throw new IllegalArgumentException("Unrecognized type of Term.");
		
		return atomManager.getAtom(atom.getPredicate(), newArgs);
	}
	
	abstract protected AbstractGroundRule groundFormulaInstance(List<GroundAtom> posLiterals, List<GroundAtom> negLiterals);

	@Override
	public void notifyAtomEvent(AtomEvent event, GroundKernelStore gks) {
		List<VariableAssignment> vars = clause.traceAtomEvent(event.getAtom());
		if (!vars.isEmpty()) {
			for (VariableAssignment var : vars) {
				DatabaseQuery dbQuery = new DatabaseQuery(clause.getQueryFormula());
				dbQuery.getPartialGrounding().putAll(var);
				ResultList res = event.getEventFramework().executeQuery(dbQuery);
				groundFormula(event.getEventFramework(), gks, res, var);
			}
		}
	}
	
	@Override
	public void registerForAtomEvents(AtomEventFramework manager) {
		clause.registerFormulaForEvents(manager, this, AtomEvent.ActivatedEventSet);
	}

	@Override
	public void unregisterForAtomEvents(AtomEventFramework manager) {
		clause.unregisterFormulaForEvents(manager, this, AtomEvent.ActivatedEventSet);
	}

	@Override
	public Kernel clone() throws CloneNotSupportedException {
		throw new CloneNotSupportedException();
	}
}
