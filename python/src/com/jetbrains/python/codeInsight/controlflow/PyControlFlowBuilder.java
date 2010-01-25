package com.jetbrains.python.codeInsight.controlflow;

import com.intellij.codeInsight.controlflow.ControlFlowBuilder;
import com.intellij.codeInsight.controlflow.ControlFlow;
import com.intellij.codeInsight.controlflow.Instruction;
import com.intellij.codeInsight.controlflow.impl.ControlFlowImpl;
import com.intellij.codeInsight.controlflow.impl.InstructionImpl;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * @author oleg
 */
public class PyControlFlowBuilder extends PyRecursiveElementVisitor {

  private final ControlFlowBuilder myBuilder = new ControlFlowBuilder();

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//// Control flow builder staff
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public ControlFlow buildControlFlow(@NotNull final ControlFlowOwner owner) {
    return myBuilder.build(this, owner);
  }

  @Override
  public void visitPyFunction(final PyFunction node) {
    // Stop here
  }

  @Override
  public void visitPyClass(final PyClass node) {
    // Stop here
  }

  @Override
  public void visitPyStatement(final PyStatement node) {
    myBuilder.startNode(node);
    super.visitPyStatement(node);
  }

  @Override
  public void visitPyAssignmentStatement(final PyAssignmentStatement node) {
    myBuilder.startNode(node);
    final PyExpression value = node.getAssignedValue();
    if (value != null) {
      value.accept(this);
    }
    for (PyExpression expression : node.getTargets()) {
      expression.accept(this);
    }
  }

  @Override
  public void visitPyTargetExpression(final PyTargetExpression node) {
    final WriteInstruction instruction = new WriteInstruction(myBuilder, node, node.getName());
    myBuilder.addNode(instruction);
    myBuilder.checkPending(instruction);
  }

  @Override
  public void visitPyNamedParameter(final PyNamedParameter node) {
    final WriteInstruction instruction = new WriteInstruction(myBuilder, node, node.getName());
    myBuilder.addNode(instruction);
    myBuilder.checkPending(instruction);
  }

  @Override
  public void visitPyImportStatement(final PyImportStatement node) {
    myBuilder.startNode(node);
    for (PyImportElement importElement : node.getImportElements()) {
      final PyReferenceExpression importReference = importElement.getImportReference();
      if (importReference != null) {
        final WriteInstruction instruction = new WriteInstruction(myBuilder, importElement, importReference.getReferencedName());
        myBuilder.addNode(instruction);
        myBuilder.checkPending(instruction);
      }
    }
  }

  private Instruction getPrevInstruction(final PyElement condition) {
    final Ref<Instruction> head = new Ref<Instruction>(myBuilder.prevInstruction);
    myBuilder.processPending(new ControlFlowBuilder.PendingProcessor() {
      public void process(final PsiElement pendingScope, final Instruction instruction) {
        if (pendingScope != null && PsiTreeUtil.isAncestor(condition, pendingScope, false)) {
          head.set(instruction);
        }
        else {
          myBuilder.addPendingEdge(pendingScope, instruction);
        }
      }
    });
    return head.get();
  }

  @Override
  public void visitPyIfStatement(final PyIfStatement node) {
    myBuilder.startNode(node);
    final PyIfPart ifPart = node.getIfPart();
    PyExpression condition = ifPart.getCondition();
    if (condition != null) {
      condition.accept(this);
    }
    // Set the head as the last instruction of condition
    Instruction head = getPrevInstruction(condition);
    myBuilder.prevInstruction = head;
    final PyStatementList thenStatements = ifPart.getStatementList();
    if (thenStatements != null) {
      myBuilder.startConditionalNode(thenStatements, condition, true);
      thenStatements.accept(this);
      myBuilder.processPending(new ControlFlowBuilder.PendingProcessor() {
        public void process(final PsiElement pendingScope, final Instruction instruction) {
          if (pendingScope != null && PsiTreeUtil.isAncestor(thenStatements, pendingScope, false)) {
            myBuilder.addPendingEdge(node, instruction);
          }
          else {
            myBuilder.addPendingEdge(pendingScope, instruction);
          }
        }
      });
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }
    for (PyIfPart part : node.getElifParts()) {
      // restore head
      myBuilder.prevInstruction = head;
      condition = part.getCondition();
      if (condition != null) {
        condition.accept(this);
      }
      // Set the head as the last instruction of condition
      head = getPrevInstruction(condition);
      myBuilder.prevInstruction = head;
      myBuilder.startConditionalNode(ifPart, condition, true);
      final PyStatementList statementList = part.getStatementList();
      if (statementList != null) {
        statementList.accept(this);
      }
      myBuilder.processPending(new ControlFlowBuilder.PendingProcessor() {
        public void process(final PsiElement pendingScope, final Instruction instruction) {
          if (pendingScope != null && PsiTreeUtil.isAncestor(ifPart, pendingScope, false)) {
            myBuilder.addPendingEdge(node, instruction);
          }
          else {
            myBuilder.addPendingEdge(pendingScope, instruction);
          }
        }
      });
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }
    // restore head
    myBuilder.prevInstruction = head;
    final PyElsePart elseBranch = node.getElsePart();
    if (elseBranch != null) {
      myBuilder.startConditionalNode(elseBranch, condition, false);
      elseBranch.accept(this);
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }

  }

  @Override
  public void visitPyWhileStatement(final PyWhileStatement node) {
    final Instruction instruction = myBuilder.startNode(node);
    final PyWhilePart whilePart = node.getWhilePart();
    final PyExpression condition = whilePart.getCondition();
    if (condition != null) {
      condition.accept(this);
    }
    final Instruction head = getPrevInstruction(condition);
    myBuilder.prevInstruction = head;

    // if condition was false
    final PyElsePart elsePart = node.getElsePart();
    if (elsePart == null) {
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }

    final PyStatementList statementList = whilePart.getStatementList();
    if (statementList != null) {
      myBuilder.startConditionalNode(statementList, condition, true);
      statementList.accept(this);
    }
    if (myBuilder.prevInstruction != null) {
      myBuilder.addEdge(myBuilder.prevInstruction, instruction); //loop
    }
    // else part
    if (elsePart != null) {
      myBuilder.startConditionalNode(statementList, condition, false);
      elsePart.accept(this);
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }
    myBuilder.flowAbrupted();
    myBuilder.checkPending(instruction); //check for breaks targeted here
  }

  @Override
  public void visitPyForStatement(final PyForStatement node) {
    myBuilder.startNode(node);
    final PyForPart forPart = node.getForPart();
    final PyExpression source = forPart.getSource();
    if (source != null) {
      source.accept(this);
    }
    final Instruction head = myBuilder.prevInstruction;
    final PyElsePart elsePart = node.getElsePart();
    if (elsePart == null) {
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }

    final PyStatementList list = forPart.getStatementList();
    if (list != null) {
      Instruction bodyInstruction = myBuilder.startNode(list);
      final PyExpression target = forPart.getTarget();
      if (target != null) {
        target.accept(this);
      }

      list.accept(this);

      if (myBuilder.prevInstruction != null) {
        myBuilder.addEdge(myBuilder.prevInstruction, bodyInstruction);  //loop
        myBuilder.addPendingEdge(node, myBuilder.prevInstruction); // exit
      }
    }
    myBuilder.prevInstruction = head;
    if (elsePart != null) {
      elsePart.accept(this);
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction); // exit
    }
    myBuilder.flowAbrupted();
  }

  @Override
  public void visitPyBreakStatement(final PyBreakStatement node) {
    final Instruction breakInstruction = new InstructionImpl(myBuilder, node);
    myBuilder.addNode(breakInstruction);
    myBuilder.checkPending(breakInstruction);
    final PyLoopStatement loop = node.getLoopStatement();
    if (loop != null) {
      myBuilder.addPendingEdge(loop, myBuilder.prevInstruction);
      myBuilder.flowAbrupted();
    }
  }

  @Override
  public void visitPyContinueStatement(final PyContinueStatement node) {
    final Instruction nextInstruction = new InstructionImpl(myBuilder, node);
    myBuilder.addNode(nextInstruction);
    myBuilder.checkPending(nextInstruction);
    final PyLoopStatement loop = node.getLoop();
    if (loop != null) {
      final Instruction instruction = myBuilder.findInstructionByElement(loop);
      if (instruction != null) {
        myBuilder.addEdge(myBuilder.prevInstruction, instruction);
        myBuilder.flowAbrupted();
      }
    }
  }

  @Override
  public void visitPyReturnStatement(final PyReturnStatement node) {
    final Instruction instruction = new InstructionImpl(myBuilder, node);
    myBuilder.addNode(instruction);
    myBuilder.checkPending(instruction);
    final PyExpression expression = node.getExpression();
    if (expression != null) {
      expression.accept(this);
    }
// Here we process pending instructions!!!
    final List<Pair<PsiElement, Instruction>> pending = myBuilder.pending;
    List<Pair<PsiElement, Instruction>> newPending = new ArrayList<Pair<PsiElement, Instruction>>();

    for (Pair<PsiElement, Instruction> pair : pending) {
      final PsiElement pendingScope = pair.getFirst();
      if (pendingScope != null && PsiTreeUtil.isAncestor(node, pendingScope, false)) {
        final Instruction pendingInstruction = pair.getSecond();
        myBuilder.addPendingEdge(null, pendingInstruction);
      }
      else {
        newPending.add(pair);
      }
    }

    myBuilder.addPendingEdge(null, myBuilder.prevInstruction);
    myBuilder.flowAbrupted();
  }

  @Override
  public void visitPyTryExceptStatement(final PyTryExceptStatement node) {
    myBuilder.startNode(node);

// process body
    final PyTryPart tryPart = node.getTryPart();
    myBuilder.startNode(tryPart);
    tryPart.accept(this);
    final Instruction lastBlockInstruction = myBuilder.prevInstruction;

// Goto else block after execution, or exit
    final PyElsePart elsePart = node.getElsePart();
    if (elsePart != null) {
      myBuilder.startNode(elsePart);
      elsePart.accept(this);
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }
    else {
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }

    final ArrayList<Instruction> exceptInstructions = new ArrayList<Instruction>();
    for (PyExceptPart exceptPart : node.getExceptParts()) {
      myBuilder.prevInstruction = lastBlockInstruction;
      final Instruction exceptInstruction = myBuilder.startNode(exceptPart);
      exceptInstructions.add(exceptInstruction);
      exceptPart.accept(this);
      myBuilder.addPendingEdge(node, myBuilder.prevInstruction);
    }

    final PyFinallyPart finallyPart = node.getFinallyPart();
    Instruction finallyInstruction = null;
    Instruction lastFinallyInstruction = null;
    if (finallyPart != null) {
      myBuilder.flowAbrupted();
      finallyInstruction = myBuilder.startNode(finallyPart);
      finallyPart.accept(this);
      lastFinallyInstruction = myBuilder.prevInstruction;
      myBuilder.addPendingEdge(finallyPart, lastFinallyInstruction);
    }
    final Ref<Instruction> finallyRef = new Ref<Instruction>(finallyInstruction);
    final Ref<Instruction> lastFinallyRef = new Ref<Instruction>(lastFinallyInstruction);
    myBuilder.processPending(new ControlFlowBuilder.PendingProcessor() {
      public void process(final PsiElement pendingScope, final Instruction instruction) {
        final PsiElement pendingElement = instruction.getElement();

        // handle raise instructions inside compound statement
        if (pendingElement instanceof PyRaiseStatement && PsiTreeUtil.isAncestor(tryPart, pendingElement, false)) {
          for (Instruction rescueInstruction : exceptInstructions) {
            myBuilder.addEdge(instruction, rescueInstruction);
          }
          return;
        }
        // handle return pending instructions inside body if ensure block exists
        if (pendingElement instanceof PyReturnStatement && !finallyRef.isNull() && PsiTreeUtil.isAncestor(node, pendingElement, false)) {
          myBuilder.addEdge(instruction, finallyRef.get());
          myBuilder.addPendingEdge(null, lastFinallyRef.get());
          return;
        }

        // Handle pending instructions inside body with ensure block
        if (pendingElement != null &&
            finallyPart != null &&
            pendingScope != finallyPart &&
            PsiTreeUtil.isAncestor(node, pendingElement, false)) {
          myBuilder.addEdge(instruction, finallyRef.get());
          return;
        }
        myBuilder.addPendingEdge(pendingScope, instruction);
      }
    });
  }

  @Override
  public void visitPyListCompExpression(final PyListCompExpression node) {
    myBuilder.startNode(node);
    for (ComprhIfComponent component : node.getIfComponents()) {
      final PyExpression condition = component.getTest();
      condition.accept(this);
      final Instruction head = myBuilder.prevInstruction;
      final Instruction prevInstruction = myBuilder.startConditionalNode(condition, condition, true);
      // restore head
      myBuilder.prevInstruction = head;
      myBuilder.addPendingEdge(node, myBuilder.startConditionalNode(condition, condition, false)); // false condition
      myBuilder.prevInstruction = prevInstruction;
    }

    for (ComprhForComponent forComponent : node.getForComponents()) {
      forComponent.getIteratedList().accept(this);
      forComponent.getIteratorVariable().accept(this);
    }

    node.getResultExpression().accept(this);
  }
}