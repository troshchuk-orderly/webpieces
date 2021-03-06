package org.webpieces.router.impl.params;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ParamValueTreeCreator {

	//A Map comes in like this (key on left and value on right)
	//  user.name = dean
	//  user.accounts[2].accName = this
	//  user.accounts[2].priority = 1
	//  user.accounts[2].addresses[0].street = MyStreet
	//  user.accounts[2].roles[0] = 'admin'
	//  user.accounts[2].roles[1] = 'manager'
	//  user.account.company.address = xxxx
	//  user.account.company.name = yyy
	//  user.account.name = zzz
	//  user.name = dean
	//  color = blue
	
	//  user.account = 111  should blow up
	public void createTree(ParamTreeNode paramTree, Map<String, String> params, FromEnum from) {
		List<String> listSubKeys = null;
		try {
			
			for(Map.Entry<String, String> entry : params.entrySet()) {
				String key = entry.getKey();
				String[] subKeys = key.split("\\.");
				if(subKeys.length == 0) {
					listSubKeys = new ArrayList<>(Arrays.asList(entry.getKey()));
				} else {
					listSubKeys = new ArrayList<>(Arrays.asList(subKeys));
				}
				createTree(paramTree, listSubKeys, entry.getValue(), key, from);
			}
		} catch (RuntimeException e) {
			throw new RuntimeException("Something bad happened with key list="+listSubKeys, e);
		}
	}
	
	private void createTree(ParamTreeNode trees, List<String> asList, String value, String fullKeyName, FromEnum from) {
		if(asList.size() == 0)
			return;
		
		String firstKey = asList.remove(0);
		if(firstKey.contains("[")) {
			createArray(trees, asList, value, fullKeyName, from, firstKey);
			return;
		}
		
		
		ParamNode node = trees.get(firstKey);
		if(node != null) {
			if(!(node instanceof ParamTreeNode))
				throw new IllegalStateException("Bug, something went wrong with key="+firstKey);
			else if(asList.size() == 0)
				throw new IllegalArgumentException("Bug, not enough subkeys...conflict in param list like user.account.id=5 "
						+ "and user.account=99 which is not allowed(since user.account would be an object so we can't set it to 99)");
			ParamTreeNode tree = (ParamTreeNode) node;
			createTree(tree, asList, value, fullKeyName, from);
			return;
		} else if(asList.size() == 0) {
			ValueNode vNode = new ValueNode(value, fullKeyName, from);
			trees.put(firstKey, vNode);
			return;
		}

		ParamTreeNode p = new ParamTreeNode();
		trees.put(firstKey, p);
		createTree(p, asList, value, fullKeyName, from);
	}

	private void createArray(ParamTreeNode trees, List<String> asList, String value, String fullKeyName,
			FromEnum from, String firstKey) {
		int indexOf = firstKey.indexOf("[");
		int nextIndex = firstKey.indexOf("]");
		String key = firstKey.substring(0, indexOf);
		String number = firstKey.substring(indexOf+1, nextIndex);
		int arrayIndex = Integer.parseInt(number);
		
		ArrayNode n;
		ParamNode paramNode = trees.get(key);
		if(paramNode != null) {
			if(!(paramNode instanceof ArrayNode))
				throw new IllegalStateException("Encountered name="+fullKeyName+" but there was another key being posted that conflicts as the original one wasn't an array");
			n = (ArrayNode) paramNode;
		} else {
			n = new ArrayNode();
			trees.put(key, n);
		}
		
		if(asList.size() > 0) {
			//complex bean
			ParamTreeNode treeNode = n.setOrGetTree(arrayIndex);
			createTree(treeNode, asList, value, fullKeyName, from);
		} else {
			//primitive String, int, etc.
			n.setElement(arrayIndex, new ValueNode(value, fullKeyName, from));
		}
	}
}
